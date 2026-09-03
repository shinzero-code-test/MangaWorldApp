package com.exapps.mangaworld.presentation.reader

import android.content.Context
import com.exapps.mangaworld.R

import android.app.Application
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.ImagePrefetcher
import com.exapps.mangaworld.core.data.ReadingPositionSyncManager
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.AchievementManager
import com.exapps.mangaworld.core.data.toDetail
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.download.ChapterDownloadWorker
import com.exapps.mangaworld.core.data.download.ChapterCleanupWorker
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Immutable
data class ReaderChapterRange(
    val chapterUrl: String = "",
    val chapterNumber: Float? = null,
    val chapterTitle: String? = null,
    val startIndex: Int = 0,
    val endIndexExclusive: Int = 0
)

@Immutable
data class ReaderUiState(
    val isLoading: Boolean = true,
    val pages: List<ChapterPage> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    // Continuous scroll: pages of auto-loaded next chapters are appended to
    // [pages] so scrolling never breaks. Ranges map global indices back to
    // their owning chapter for progress / read-marking.
    val chapterRanges: List<ReaderChapterRange> = emptyList(),
    val isAppendingNextChapter: Boolean = false,
    val showControls: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.VERTICAL_SCROLL,
    val brightness: Float = 1.0f,
    val error: String? = null,
    val chapterUrl: String = "",
    val chapterNumber: Float? = null,
    val chapterTitle: String? = null,
    val mangaId: String = "",
    val downloadInProgress: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadMessage: String? = null,
    val activeDownloadTaskId: String? = null,
    val downloadOnWifiOnly: Boolean = true,
    val incognitoMode: Boolean = false,
    val imageFilter: ReaderImageFilter = ReaderImageFilter.NONE,
    val hapticsEnabled: Boolean = true,
    val smartPrefetchEnabled: Boolean = true,
    val secureReaderEnabled: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val pageSpacing: Int = 0,
    val volumeButtonPageTurn: Boolean = false,
    val doubleTapZoom: Boolean = true,
    val prevChapterUrl: String? = null,
    val nextChapterUrl: String? = null,
    val bookmarkedPages: Set<Int> = emptySet(),
    val pageNotes: Map<Int, String> = emptyMap(),
    val liveReaders: Int = 0,
    val currentPageReactions: List<ReaderReaction> = emptyList(),
    val autoOpenNextChapter: Boolean = false,
    val showLiveReadersOverlay: Boolean = true,
    val showReactionOverlay: Boolean = true,
    val dualPageLandscape: Boolean = false,
    val webtoonAutoStitch: Boolean = true,
    val spoilerCollapseDefault: Boolean = true,
    val chapterComments: List<CommunityComment> = emptyList(),
    /** Typed download-failure signal for the retry affordance — never derive UI from display text. */
    val lastDownloadFailed: Boolean = false,
    val chapterCommentError: String? = null,
    val lastTapNormalizedX: Float = 0.5f,
    val lastTapNormalizedY: Float = 0.5f
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val app: Application,
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val communityRepository: CommunityRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val cacheDao: MangaCacheDao,
    private val readingStatsStore: ReadingStatsStore,
    private val achievementManager: AchievementManager,
    private val imagePrefetcher: ImagePrefetcher,
    private val firebaseSyncManager: FirebaseSyncManager,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val positionSyncManager: ReadingPositionSyncManager,
    private val imageLoader: coil.ImageLoader
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var currentSource: MangaSource = MangaSource.STARZ
    private var sessionCheckpointAt: Long? = null
    // Scope that outlives viewModelScope cancellation so end-of-session flushes
    // (presence-off, reading-time) actually run from onCleared.
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var annotationsJob: Job? = null
    private var presenceJob: Job? = null
    private var reactionsJob: Job? = null
    private var commentsJob: Job? = null
    private var downloadTaskJob: Job? = null
    private var appendJob: Job? = null
    private var prefetchedNextChapterUrl: String? = null
    private var lastReadAnalyticsKey: String? = null
    private var periodicSaveJob: kotlinx.coroutines.Job? = null
    private val activeDownloadStatuses = setOf("queued", "running", "paused")

    init {
        viewModelScope.launch {
            settingsRepo.getReaderSettings().collect { settings ->
                _state.update {
                    it.copy(
                        readerMode = settings.mode,
                        brightness = settings.brightness,
                        incognitoMode = settings.incognitoMode,
                        imageFilter = settings.imageFilter,
                        hapticsEnabled = settings.hapticsEnabled,
                        smartPrefetchEnabled = settings.smartPrefetchEnabled,
                        autoOpenNextChapter = settings.autoOpenNextChapter,
                        showLiveReadersOverlay = settings.showLiveReadersOverlay,
                        showReactionOverlay = settings.showReactionOverlay,
                        dualPageLandscape = settings.dualPageLandscape,
                        webtoonAutoStitch = settings.webtoonAutoStitch,
                        pageSpacing = settings.pageSpacing,
                        volumeButtonPageTurn = settings.volumeButtonPageTurn,
                        doubleTapZoom = settings.doubleTapZoom,
                        showPageNumber = settings.showPageNumber,
                        keepScreenOn = settings.keepScreenOn
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.getAppSettings().collect { settings ->
                _state.update {
                    it.copy(
                        downloadOnWifiOnly = settings.downloadOnWifiOnly,
                        secureReaderEnabled = settings.secureReaderEnabled,
                        spoilerCollapseDefault = settings.spoilerCollapseDefault
                    )
                }
            }
        }
    }

    fun loadChapter(chapterUrl: String, mangaId: String, source: MangaSource) {
        if (_state.value.chapterUrl != chapterUrl) {
            finishSessionAsync()
            stopCommunityPresenceAsync(_state.value.mangaId, _state.value.chapterUrl)
        }
        currentSource = source
        prefetchedNextChapterUrl = null
        appendJob?.cancel()
        _state.update {
            it.copy(
                isLoading = true,
                error = null,
                chapterUrl = chapterUrl,
                mangaId = mangaId,
                pages = emptyList(),
                totalPages = 0,
                currentPage = 0,
                chapterRanges = emptyList(),
                isAppendingNextChapter = false
            )
        }

        // For imported/local manga — read from disk only, no network
        val isLocal = mangaId.startsWith("imported_") || source.id == "local" || source.id == "imported"
        if (isLocal) {
            viewModelScope.launch {
                val localPages = withContext(Dispatchers.IO) {
                    downloadQueueManager.getLocalChapterPages(mangaId, chapterUrl)
                }
                if (localPages.isNotEmpty()) {
                    val indexed = localPages.mapIndexed { i, p -> p.copy(index = i) }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pages = indexed,
                            totalPages = indexed.size,
                            currentPage = 0,
                            chapterNumber = parseFallbackChapterNumber(chapterUrl),
                            chapterRanges = listOf(
                                ReaderChapterRange(
                                    chapterUrl = chapterUrl,
                                    chapterNumber = parseFallbackChapterNumber(chapterUrl),
                                    chapterTitle = null,
                                    startIndex = 0,
                                    endIndexExclusive = indexed.size
                                )
                            ),
                            downloadMessage = context.getString(R.string.read_offline)
                        )
                    }
                    computeAdjacentLocalChapters(mangaId)
                    beginSession(mangaId, chapterUrl)
                } else {
                    _state.update { it.copy(isLoading = false, error = context.getString(R.string.no_pages_loaded)) }
                }
            }
            return
        }

        // Pull remote positions to get latest from other devices
        viewModelScope.launch {
            runCatching { positionSyncManager.pullRemotePositions() }
        }
        viewModelScope.launch {
            val chapterMeta = resolveChapterMeta(mangaId, chapterUrl, source)
            val localPages = withContext(Dispatchers.IO) {
                downloadQueueManager.getLocalChapterPages(mangaId, chapterUrl)
            }
            if (localPages.isNotEmpty()) {
                val currentChapterNumber = chapterMeta?.number ?: parseFallbackChapterNumber(chapterUrl)
                val indexed = localPages.mapIndexed { i, p -> p.copy(index = i) }
                _state.update {
                    it.copy(
                        isLoading = false,
                        pages = indexed,
                        totalPages = indexed.size,
                        currentPage = 0,
                        chapterNumber = currentChapterNumber,
                        chapterTitle = chapterMeta?.title,
                        chapterRanges = listOf(
                            ReaderChapterRange(
                                chapterUrl = chapterUrl,
                                chapterNumber = currentChapterNumber,
                                chapterTitle = chapterMeta?.title,
                                startIndex = 0,
                                endIndexExclusive = indexed.size
                            )
                        ),
                        downloadMessage = context.getString(R.string.read_offline)
                    )
                }
                observeAnnotations(mangaId, chapterUrl)
                observeCommunity(mangaId, chapterUrl)
                computeAdjacentChapters(mangaId, chapterUrl, source)
                beginSession(mangaId, chapterUrl)
                return@launch
            }
            mangaRepo.getChapterPages("", chapterUrl, source)
                .onSuccess { pages ->
                    // Restore saved progress
                    val chNum = chapterMeta?.number ?: parseFallbackChapterNumber(chapterUrl)
                    val (savedPage, _) = libraryRepo.getReadingProgress(mangaId, chNum)
                    val indexed = pages.mapIndexed { i, p -> p.copy(index = i) }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pages = indexed,
                            totalPages = indexed.size,
                            currentPage = savedPage.coerceIn(0, maxOf(0, indexed.size - 1)),
                            chapterNumber = chNum,
                            chapterTitle = chapterMeta?.title,
                            chapterRanges = listOf(
                                ReaderChapterRange(
                                    chapterUrl = chapterUrl,
                                    chapterNumber = chNum,
                                    chapterTitle = chapterMeta?.title,
                                    startIndex = 0,
                                    endIndexExclusive = indexed.size
                                )
                            )
                        )
                    }
                    // Update reading history so Library shows last-visited manga
                    if (!_state.value.incognitoMode) {
                        runCatching {
                            val cached = cacheDao.get(mangaId)
                            if (cached != null) {
                                libraryRepo.updateReadingHistory(
                                    mangaId = mangaId, slug = cached.slug,
                                    title = cached.title, coverUrl = cached.coverUrl,
                                    source = MangaSource.fromId(cached.sourceId),
                                    chapterNumber = chNum,
                                    chapterUrl = chapterUrl,
                                    totalChapters = cached.totalChapters ?: 0
                                )
                            }
                        }
                    }
                    observeAnnotations(mangaId, chapterUrl)
                    observeCommunity(mangaId, chapterUrl)
                    computeAdjacentChapters(mangaId, chapterUrl, source)
                    beginSession(mangaId, chapterUrl)
                    if (!_state.value.incognitoMode) {
                        viewModelScope.launch { runCatching { firebaseSyncManager.pushLocalSnapshot() } }
                    }
                    viewModelScope.launch { widgetShortcutCoordinator.refreshWidgetsAndShortcuts() }
                }
                .onFailure { e ->
                    val msg = if (e is CloudflareChallengeException) {
                        "CLOUDFLARE_REQUIRED|${e.domain}|${e.targetUrl}"
                    } else e.message
                    _state.update { it.copy(isLoading = false, error = msg) }
                }
        }
    }

    fun onCloudflareSolved(domain: String, cookies: String) {
        viewModelScope.launch {
            settingsRepo.saveCookies(domain, cookies)
            delay(300)
            val st = _state.value
            loadChapter(st.chapterUrl, st.mangaId, currentSource)
        }
    }

    fun onPageChanged(page: Int) {
        val st = _state.value
        if (page == st.currentPage && st.chapterRanges.isNotEmpty()) {
            // Still trigger continuous prefetch when settling on same index
            // (e.g. small last page that never reaches top).
            if (st.autoOpenNextChapter) ensureContinuousNextChapter(page, st) else if (st.smartPrefetchEnabled && st.totalPages > 0 && page >= (st.totalPages / 2)) {
                viewModelScope.launch { prefetchNextChapterIfNeeded(st) }
            }
            return
        }
        // Resolve the owning chapter for this global index (continuous mode).
        val activeRange = st.chapterRanges.firstOrNull { page in it.startIndex until it.endIndexExclusive }
        val activeChapterUrl = activeRange?.chapterUrl ?: st.chapterUrl
        val activeChapterNumber = activeRange?.chapterNumber ?: st.chapterNumber
        val activeChapterTitle = activeRange?.chapterTitle ?: st.chapterTitle
        val pageInChapter = if (activeRange != null) page - activeRange.startIndex else page
        val chapterTotal = if (activeRange != null) activeRange.endIndexExclusive - activeRange.startIndex else st.totalPages
        val chapterChanged = activeChapterUrl != st.chapterUrl
        _state.update {
            it.copy(
                currentPage = page,
                chapterUrl = activeChapterUrl,
                chapterNumber = activeChapterNumber,
                chapterTitle = activeChapterTitle
            )
        }
        // Save progress & mark read at last page
        viewModelScope.launch {
            // Resolve once, then cache in state so fast page-flips never re-hit
            // the network for chapter metadata.
            val cachedNumber = activeChapterNumber
            val chNum = cachedNumber
                ?: (resolveChapterMeta(st.mangaId, activeChapterUrl, currentSource)?.number
                    ?: parseFallbackChapterNumber(activeChapterUrl))
                .also { resolved -> if (cachedNumber == null) _state.update { it.copy(chapterNumber = resolved) } }
            trackReadingTime()
            if (!st.incognitoMode) {
                libraryRepo.saveReadingProgress(st.mangaId, chNum, pageInChapter, chapterTotal)
                // Sync position to cloud every 5 pages
                if (page % 5 == 0) {
                    runCatching { positionSyncManager.pushLocalPositions() }
                }
            }
            observeReactions(st.mangaId, activeChapterUrl, pageInChapter)
            if (chapterChanged) {
                // Switched into an appended chapter — move annotations/presence
                // so bookmarks, comments and live-readers follow the visible chapter.
                observeAnnotations(st.mangaId, activeChapterUrl)
                observeCommunity(st.mangaId, activeChapterUrl)
            }
            if (st.smartPrefetchEnabled && st.totalPages > 0 && page >= (st.totalPages / 2)) {
                prefetchNextChapterIfNeeded(_state.value)
            }
            // Continuous mode: append next chapter when near the end instead of
            // waiting for the last page top to be visible (small pages never
            // reach it). Works for short and tall pages alike.
            if (st.autoOpenNextChapter) {
                ensureContinuousNextChapter(page, _state.value)
            }
            val isLastGlobalPage = page >= _state.value.totalPages - 1
            val isEndOfItsChapter = activeRange != null && page >= activeRange.endIndexExclusive - 1
            if (isEndOfItsChapter || (activeRange == null && isLastGlobalPage)) {
                if (!st.incognitoMode) {
                    libraryRepo.markChapterRead(st.mangaId, chNum)
                    achievementManager.recordChapterRead()
                    readingStatsStore.incrementMangaRead()
                    scheduleAutoCleanupIfNeeded(st.mangaId, activeChapterUrl)
                    runCatching { firebaseSyncManager.pushLocalSnapshot() }
                    val analyticsKey = "${st.mangaId}|$activeChapterUrl|$chNum"
                    if (lastReadAnalyticsKey != analyticsKey) {
                        lastReadAnalyticsKey = analyticsKey
                        analyticsManager.logChapterRead(
                            mangaId = st.mangaId,
                            sourceId = currentSource.id,
                            chapterNumber = chNum,
                            totalPages = chapterTotal,
                            readerMode = st.readerMode.name
                        )
                    }
                }
                // Legacy fallback: if continuous is off but auto-next is on,
                // jump discretely (previous behaviour).
                if (st.autoOpenNextChapter && _state.value.chapterRanges.size <= 1) {
                    _state.value.nextChapterUrl?.let { next -> loadChapter(next, st.mangaId, currentSource) }
                }
                widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
            }
        }
    }

    /**
     * Append the next chapter's pages to the current list when the user scrolls
     * within [PREFETCH_THRESHOLD] pages of the end. The next chapter then
     * appears as a continuation — no tap needed, no blank jump.
     */
    private fun ensureContinuousNextChapter(globalPage: Int, st: ReaderUiState) {
        if (!st.autoOpenNextChapter) return
        if (st.isAppendingNextChapter) return
        if (st.totalPages <= 0) return
        if (globalPage < st.totalPages - PREFETCH_THRESHOLD) return
        val nextUrl = st.nextChapterUrl ?: return
        // Already appended this chapter (ranges contain it).
        if (st.chapterRanges.any { it.chapterUrl == nextUrl }) return
        if (appendJob?.isActive == true) return
        appendJob = viewModelScope.launch {
            _state.update { it.copy(isAppendingNextChapter = true) }
            try {
                val mangaId = st.mangaId
                val isLocalManga = mangaId.startsWith("imported_")
                val fetched: List<ChapterPage> = if (isLocalManga) {
                    withContext(Dispatchers.IO) {
                        downloadQueueManager.getLocalChapterPages(mangaId, nextUrl)
                    }
                } else {
                    val local = withContext(Dispatchers.IO) {
                        downloadQueueManager.getLocalChapterPages(mangaId, nextUrl)
                    }
                    if (local.isNotEmpty()) local
                    else mangaRepo.getChapterPages("", nextUrl, currentSource).getOrNull().orEmpty()
                }
                if (fetched.isEmpty()) {
                    _state.update { it.copy(isAppendingNextChapter = false) }
                    return@launch
                }
                val meta = resolveChapterMeta(mangaId, nextUrl, currentSource)
                val chNum = meta?.number ?: parseFallbackChapterNumber(nextUrl)
                val base = _state.value.pages.size
                val indexed = fetched.mapIndexed { i, p -> p.copy(index = base + i) }
                val newRange = ReaderChapterRange(
                    chapterUrl = nextUrl,
                    chapterNumber = chNum,
                    chapterTitle = meta?.title,
                    startIndex = base,
                    endIndexExclusive = base + indexed.size
                )
                _state.update { cur ->
                    val combined = cur.pages + indexed
                    cur.copy(
                        pages = combined,
                        totalPages = combined.size,
                        chapterRanges = cur.chapterRanges + newRange,
                        isAppendingNextChapter = false
                    )
                }
                // Advance the next/prev pointers so a second append chains to the
                // chapter after the one just appended.
                if (isLocalManga) {
                    computeAdjacentLocalChaptersFor(mangaId, nextUrl)
                } else {
                    computeAdjacentChapters(mangaId, nextUrl, currentSource)
                }
                if (!st.incognitoMode) {
                    imagePrefetcher.prefetchPages(indexed.take(6))
                }
            } catch (_: Exception) {
                _state.update { it.copy(isAppendingNextChapter = false) }
            }
        }
    }

    /** Local/imported manga: chapters are on-disk dirs — resolve prev/next from metadata. */
    private suspend fun computeAdjacentLocalChapters(mangaId: String) {
        computeAdjacentLocalChaptersFor(mangaId, _state.value.chapterUrl)
        val st = _state.value
        // Imported local chapters also support continuous scroll.
        if (st.autoOpenNextChapter) ensureContinuousNextChapter(st.currentPage, st)
    }

    private suspend fun computeAdjacentLocalChaptersFor(mangaId: String, currentUrl: String) {
        try {
            // Reuse disk scan order from detail (metadata + .completed dirs).
            val mangaDirPath = withContext(Dispatchers.IO) {
                downloadQueueManager.getMangaDirPath(mangaId)
            }
            val mangaDir = java.io.File(mangaDirPath)
            val ordered = mangaDir.listFiles()
                ?.filter { it.isDirectory && java.io.File(it, ".completed").exists() }
                ?.sortedBy { it.name.replace("[^0-9.]".toRegex(), "").toFloatOrNull() ?: 0f }
                ?.map { it.name }
                .orEmpty()
            val idx = ordered.indexOf(currentUrl)
            _state.update {
                it.copy(
                    nextChapterUrl = if (idx >= 0) ordered.getOrNull(idx + 1) else null,
                    prevChapterUrl = if (idx > 0) ordered.getOrNull(idx - 1) else null
                )
            }
        } catch (_: Exception) { }
    }

    companion object {
        /** Start appending when within this many pages of the combined end. */
        private const val PREFETCH_THRESHOLD = 3
    }

    fun toggleControls() = _state.update { it.copy(showControls = !it.showControls) }
    fun setReaderMode(mode: ReaderMode) {
        _state.update { it.copy(readerMode = mode) }
        viewModelScope.launch { settingsRepo.updateReaderMode(mode) }
    }

    fun downloadCurrentChapter() {
        val st = _state.value
        // Imported chapters are already local files — nothing to download.
        if (st.mangaId.startsWith("imported_")) return
        if (st.pages.isEmpty() || st.downloadInProgress) return
        val taskId = "${st.mangaId}_${st.chapterUrl.hashCode()}"
        viewModelScope.launch {
            val referer = st.pages.firstOrNull()?.headers?.get("Referer")
                ?.takeIf { it.isNotBlank() }
                ?: st.chapterUrl
            // Resolve proper manga title from cache instead of using the slug
            val mangaTitle = withContext(Dispatchers.IO) {
                cacheDao.get(st.mangaId)?.title
                    ?: resolveDetailForChapter(st.mangaId, currentSource)?.title
                    ?: st.mangaId.substringAfter("_").ifBlank { st.mangaId }
            }
            _state.update { it.copy(downloadInProgress = true, downloadProgress = 0f, downloadMessage = context.getString(R.string.starting_download), lastDownloadFailed = false, activeDownloadTaskId = taskId) }
            val wasEnqueued = downloadQueueManager.enqueueAndRun(
                taskId = taskId,
                mangaId = st.mangaId,
                mangaTitle = mangaTitle,
                chapterUrl = st.chapterUrl,
                chapterTitle = st.chapterTitle,
                pages = st.pages,
                wifiOnly = st.downloadOnWifiOnly,
                referer = referer
            )
            if (wasEnqueued) {
                observeDownloadTask(taskId)
            } else {
                val existingTaskId = downloadQueueManager.pendingTaskId(st.mangaId, st.chapterUrl)
                if (existingTaskId != null) {
                    _state.update { it.copy(activeDownloadTaskId = existingTaskId) }
                    observeDownloadTask(existingTaskId)
                } else {
                    // Disk scan must leave the Main thread; resolve before updating state.
                    val alreadyOwned = withContext(Dispatchers.IO) {
                        downloadQueueManager.isChapterDownloaded(st.mangaId, st.chapterUrl, mangaTitle)
                    }
                    _state.update {
                        it.copy(
                            downloadInProgress = false,
                            activeDownloadTaskId = null,
                            downloadMessage = if (alreadyOwned) {
                                context.getString(R.string.read_offline)
                            } else {
                                context.getString(R.string.pending)
                            }
                        )
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        val taskId = _state.value.activeDownloadTaskId ?: return
        viewModelScope.launch {
            downloadQueueManager.cancelTask(taskId)
        }
    }

    fun retryCurrentChapterDownload() {
        downloadCurrentChapter()
    }

    private fun observeDownloadTask(taskId: String) {
        downloadTaskJob?.cancel()
        downloadTaskJob = viewModelScope.launch {
            downloadQueueManager.observeTask(taskId).collect { task ->
                if (task == null || _state.value.activeDownloadTaskId != taskId) return@collect
                _state.update {
                    it.copy(
                        downloadInProgress = task.status in activeDownloadStatuses,
                        downloadProgress = task.progress,
                        downloadMessage = task.errorMessage.toDownloadCodeMessage()
                            ?: task.errorMessage?.takeIf { it.isNotBlank() }
                            ?: task.status.toDownloadMessage(),
                        // Typed signal drives the retry affordance (never string comparison).
                        lastDownloadFailed = task.status == "failed",
                        activeDownloadTaskId = task.id
                    )
                }
            }
        }
    }

    private fun String.toDownloadMessage(): String? = when (this) {
        "queued" -> context.getString(R.string.pending)
        "running" -> context.getString(R.string.starting_download)
        "paused" -> context.getString(R.string.stopped)
        "cancelled" -> context.getString(R.string.cancelled)
        "failed" -> context.getString(R.string.download_error)
        else -> null
    }

    /**
     * Translates stable, language-neutral error codes persisted in Room into localized text.
     * Returns null for anything else so legacy rows (which stored human-readable, already
     * localized messages) keep rendering verbatim.
     */
    private fun String?.toDownloadCodeMessage(): String? = when (this) {
        ChapterDownloadWorker.ERROR_CANCELLED -> context.getString(R.string.cancelled)
        ChapterDownloadWorker.ERROR_RETRY_UNAVAILABLE -> context.getString(R.string.download_retry_unavailable)
        ChapterDownloadWorker.ERROR_DOWNLOAD_FAILED -> context.getString(R.string.download_error)
        else -> null
    }

    fun postReaderComment(text: String, spoiler: Boolean) {
        val st = _state.value
        val slug = st.mangaId.substringAfter('_')
        viewModelScope.launch {
            // Surface failures — a silent runCatching left guests (or flaky networks)
            // staring at a cleared composer with zero feedback.
            runCatching {
                communityRepository.postChapterComment(
                    mangaId = st.mangaId,
                    slug = slug,
                    sourceId = currentSource.id,
                    chapterUrl = st.chapterUrl,
                    text = text,
                    spoiler = spoiler
                )
            }.onFailure {
                _state.update {
                    it.copy(chapterCommentError = context.getString(R.string.error_generic))
                }
            }
        }
    }

    fun setBrightness(value: Float) = viewModelScope.launch {
        settingsRepo.updateBrightness(value)
    }

    fun setImageFilter(filter: ReaderImageFilter) = viewModelScope.launch {
        settingsRepo.updateImageFilter(filter)
    }

    fun setAutoOpenNextChapter(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateAutoOpenNextChapter(enabled)
    }

    fun setShowLiveReadersOverlay(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateShowLiveReadersOverlay(enabled)
    }

    fun setShowReactionOverlay(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateShowReactionOverlay(enabled)
    }

    fun setDualPageLandscape(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateDualPageLandscape(enabled)
    }

    fun setWebtoonAutoStitch(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateWebtoonAutoStitch(enabled)
    }

    fun setIncognito(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateIncognitoMode(enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateKeepScreenOn(enabled)
    }

    fun setHaptics(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateReaderHaptics(enabled)
    }

    fun setSmartPrefetch(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateSmartPrefetch(enabled)
    }

    fun setPageSpacing(spacing: Int) = viewModelScope.launch {
        settingsRepo.updatePageSpacing(spacing)
        _state.update { it.copy(pageSpacing = spacing) }
    }

    fun setVolumeButton(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateVolumeButtonPageTurn(enabled)
        _state.update { it.copy(volumeButtonPageTurn = enabled) }
    }

    fun setDoubleTapZoom(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateDoubleTapZoom(enabled)
        _state.update { it.copy(doubleTapZoom = enabled) }
    }

    fun setShowPageNumber(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateShowPageNumber(enabled)
        _state.update { it.copy(showPageNumber = enabled) }
    }

    fun onReaderTap(normalizedX: Float, normalizedY: Float) {
        _state.update {
            it.copy(
                lastTapNormalizedX = normalizedX.coerceIn(0f, 1f),
                lastTapNormalizedY = normalizedY.coerceIn(0f, 1f)
            )
        }
    }

    fun saveCurrentPage() {
        val st = _state.value
        val page = st.pages.getOrNull(st.currentPage) ?: return
        viewModelScope.launch {
            try {
                val context = app.applicationContext
                val bitmap = imageLoader.execute(
                    coil.request.ImageRequest.Builder(context)
                        .data(page.url)
                        .allowHardware(false)
                        .build()
                ).drawable?.let { drawable ->
                    val bmp = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                } ?: return@launch
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "MangaWorld_${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangaWorld")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                    }
                }
                _state.update { it.copy(downloadMessage = context.getString(R.string.reader_page_saved)) }
                kotlinx.coroutines.delay(2000)
                _state.update { it.copy(downloadMessage = null) }
            } catch (_: Exception) {
                _state.update { it.copy(downloadMessage = context.getString(R.string.str_337)) }
                kotlinx.coroutines.delay(2000)
                _state.update { it.copy(downloadMessage = null) }
            }
        }
    }

    fun toggleBookmarkCurrentPage() {
        val st = _state.value
        viewModelScope.launch {
            libraryRepo.togglePageBookmark(st.mangaId, st.chapterUrl, st.currentPage)
        }
    }

    suspend fun getCurrentPageNote(): String =
        libraryRepo.getPageAnnotation(_state.value.mangaId, _state.value.chapterUrl, _state.value.currentPage)?.note.orEmpty()

    fun saveCurrentPageNote(note: String) {
        val st = _state.value
        viewModelScope.launch {
            libraryRepo.savePageNote(st.mangaId, st.chapterUrl, st.currentPage, note)
        }
    }

    fun openPreviousChapter() {
        val st = _state.value
        st.prevChapterUrl?.let { loadChapter(it, st.mangaId, currentSource) }
    }

    fun openNextChapter() {
        val st = _state.value
        st.nextChapterUrl?.let { loadChapter(it, st.mangaId, currentSource) }
    }

    fun sendReaction(emoji: String) {
        val st = _state.value
        viewModelScope.launch {
            runCatching {
                communityRepository.sendPageReaction(
                    st.mangaId,
                    st.chapterUrl,
                    st.currentPage,
                    emoji,
                    st.lastTapNormalizedX,
                    st.lastTapNormalizedY
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadTaskJob?.cancel()
        stopCommunityPresenceAsync(_state.value.mangaId, _state.value.chapterUrl)
        finishSessionAsync()
        // Flushes above were handed to flushScope; only now is it safe to cancel.
        flushScope.cancel()
    }

    private fun beginSession(mangaId: String, chapterUrl: String) {
        sessionCheckpointAt = System.currentTimeMillis()
        // Periodically save reading time to prevent data loss on force-kill (every 30 seconds)
        periodicSaveJob?.cancel()
        periodicSaveJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                trackReadingTime()
            }
        }
    }

    private fun observeAnnotations(mangaId: String, chapterUrl: String) {
        annotationsJob?.cancel()
        annotationsJob = viewModelScope.launch {
            libraryRepo.observeReaderAnnotations(mangaId, chapterUrl).collect { annotations ->
                _state.update {
                    it.copy(
                        bookmarkedPages = annotations.filter { it.isBookmarked }.map { it.pageIndex }.toSet(),
                        pageNotes = annotations.filter { !it.note.isNullOrBlank() }.associate { ann -> ann.pageIndex to ann.note.orEmpty() }
                    )
                }
            }
        }
    }

    private fun observeCommunity(mangaId: String, chapterUrl: String) {
        presenceJob?.cancel()
        reactionsJob?.cancel()
        commentsJob?.cancel()
        presenceJob = viewModelScope.launch {
            runCatching { communityRepository.setReaderPresence(mangaId, chapterUrl, true) }
            communityRepository.observeReaderPresenceCount(mangaId, chapterUrl).collect { count ->
                _state.update { it.copy(liveReaders = count) }
            }
        }
        observeReactions(mangaId, chapterUrl, _state.value.currentPage)
        commentsJob = viewModelScope.launch {
            communityRepository.observeChapterComments(mangaId, chapterUrl).collect { comments ->
                _state.update { it.copy(chapterComments = comments) }
            }
        }
    }

    private fun observeReactions(mangaId: String, chapterUrl: String, pageIndex: Int) {
        reactionsJob?.cancel()
        reactionsJob = viewModelScope.launch {
            communityRepository.observePageReactions(mangaId, chapterUrl, pageIndex).collect { reactions ->
                _state.update { it.copy(currentPageReactions = reactions) }
            }
        }
    }

    private suspend fun computeAdjacentChapters(mangaId: String, chapterUrl: String, source: MangaSource) {
        val detail = resolveDetailForChapter(mangaId, source)
        val chapters = detail?.chapters.orEmpty().sortedByDescending { it.number }
        val currentIndex = chapters.indexOfFirst { it.url == chapterUrl }
        if (currentIndex == -1) return
        _state.update {
            it.copy(
                nextChapterUrl = chapters.getOrNull(currentIndex - 1)?.url,
                prevChapterUrl = chapters.getOrNull(currentIndex + 1)?.url
            )
        }
    }

    private suspend fun prefetchNextChapterIfNeeded(state: ReaderUiState) {
        val nextUrl = state.nextChapterUrl ?: return
        if (prefetchedNextChapterUrl == nextUrl) return
        val pages = mangaRepo.getChapterPages("", nextUrl, currentSource).getOrNull().orEmpty()
        if (pages.isEmpty()) return
        imagePrefetcher.prefetchPages(pages)
        prefetchedNextChapterUrl = nextUrl
    }

    private suspend fun resolveDetailForChapter(mangaId: String, source: MangaSource): MangaDetail? =
        if (source.id == "local") null
        else cacheDao.get(mangaId)?.toDetail(source) ?: mangaRepo.getMangaDetail(mangaId.substringAfter("_"), source).getOrNull()

    private suspend fun resolveChapterMeta(mangaId: String, chapterUrl: String, source: MangaSource): Chapter? =
        resolveDetailForChapter(mangaId, source)?.chapters?.firstOrNull { it.url == chapterUrl }

    private fun parseFallbackChapterNumber(chapterUrl: String): Float =
        chapterUrl.substringAfterLast("/").replace("[^0-9.]".toRegex(), "").toFloatOrNull() ?: 0f

    private suspend fun scheduleAutoCleanupIfNeeded(mangaId: String, chapterUrl: String) {
        val settings = settingsRepo.getAppSettings().first()
        if (!settings.autoCleanupReadDownloads) return
        if (downloadQueueManager.getDownloadedChapterDir(mangaId, chapterUrl) == null) return
        val request = OneTimeWorkRequestBuilder<ChapterCleanupWorker>()
            .setInitialDelay(settings.cleanupAfterHours.toLong(), TimeUnit.HOURS)
            .setInputData(
                Data.Builder()
                    .putString(ChapterCleanupWorker.KEY_MANGA_ID, mangaId)
                    .putString(ChapterCleanupWorker.KEY_CHAPTER_URL, chapterUrl)
                    .build()
            )
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "cleanup_${mangaId}_${chapterUrl.hashCode()}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private suspend fun trackReadingTime() {
        if (_state.value.incognitoMode) return
        val checkpoint = sessionCheckpointAt ?: return
        val now = System.currentTimeMillis()
        val delta = (now - checkpoint).coerceIn(0L, 2 * 60_000L)
        if (delta >= 1_000L) {
            readingStatsStore.addReadingTime(delta)
            readingStatsStore.recordPageRead(1)
            // Keep achievements/goals in sync
            achievementManager.recordPageRead(1)
        }
        sessionCheckpointAt = now
    }

    private fun finishSessionAsync() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        if (_state.value.incognitoMode) {
            sessionCheckpointAt = null
            return
        }
        val checkpoint = sessionCheckpointAt ?: return
        val now = System.currentTimeMillis()
        val delta = (now - checkpoint).coerceIn(0L, 2 * 60_000L)
        sessionCheckpointAt = null
        if (delta >= 1_000L) {
            // viewModelScope is already cancelled inside onCleared — use flushScope.
            flushScope.launch {
                runCatching { readingStatsStore.addReadingTime(delta) }
                runCatching { widgetShortcutCoordinator.refreshWidgets() }
            }
        }
    }

    private fun stopCommunityPresenceAsync(mangaId: String, chapterUrl: String) {
        if (mangaId.isBlank() || chapterUrl.isBlank()) return
        presenceJob?.cancel()
        reactionsJob?.cancel()
        commentsJob?.cancel()
        flushScope.launch {
            runCatching { communityRepository.setReaderPresence(mangaId, chapterUrl, false) }
        }
    }
}
