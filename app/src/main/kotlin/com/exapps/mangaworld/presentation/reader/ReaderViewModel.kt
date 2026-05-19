package com.exapps.mangaworld.presentation.reader

import android.app.Application
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.ImagePrefetcher
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.toDetail
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.download.ChapterCleanupWorker
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

data class ReaderUiState(
    val isLoading: Boolean = true,
    val pages: List<ChapterPage> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val showControls: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.VERTICAL_SCROLL,
    val brightness: Float = 1.0f,
    val error: String? = null,
    val chapterUrl: String = "",
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
    val prevChapterUrl: String? = null,
    val nextChapterUrl: String? = null,
    val bookmarkedPages: Set<Int> = emptySet(),
    val pageNotes: Map<Int, String> = emptyMap()
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val app: Application,
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val cacheDao: MangaCacheDao,
    private val readingStatsStore: ReadingStatsStore,
    private val imagePrefetcher: ImagePrefetcher,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var currentSource: MangaSource = MangaSource.STARZ
    private var activeSessionKey: String? = null
    private var sessionCheckpointAt: Long? = null
    private var annotationsJob: Job? = null
    private var prefetchedNextChapterUrl: String? = null

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
                        smartPrefetchEnabled = settings.smartPrefetchEnabled
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.getAppSettings().collect { settings ->
                _state.update {
                    it.copy(
                        downloadOnWifiOnly = settings.downloadOnWifiOnly,
                        secureReaderEnabled = settings.secureReaderEnabled
                    )
                }
            }
        }
    }

    fun loadChapter(chapterUrl: String, mangaId: String, source: MangaSource) {
        if (_state.value.chapterUrl != chapterUrl) {
            finishSessionAsync()
        }
        currentSource = source
        prefetchedNextChapterUrl = null
        _state.update { it.copy(isLoading = true, error = null, chapterUrl = chapterUrl, mangaId = mangaId) }
        viewModelScope.launch {
            val localPages = downloadQueueManager.getLocalChapterPages(mangaId, chapterUrl)
            if (localPages.isNotEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        pages = localPages,
                        totalPages = localPages.size,
                        currentPage = 0,
                        downloadMessage = "قراءة بدون إنترنت"
                    )
                }
                observeAnnotations(mangaId, chapterUrl)
                computeAdjacentChapters(mangaId, chapterUrl, source)
                beginSession(mangaId, chapterUrl)
                return@launch
            }
            mangaRepo.getChapterPages("", chapterUrl, source)
                .onSuccess { pages ->
                    // Restore saved progress
                    val slug = mangaId.substringAfter("_")
                    val chNum = chapterUrl.substringAfterLast("/").replace("[^0-9.]".toRegex(), "")
                        .toFloatOrNull() ?: 0f
                    val (savedPage, _) = libraryRepo.getReadingProgress(mangaId, chNum)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pages = pages,
                            totalPages = pages.size,
                            currentPage = savedPage.coerceIn(0, maxOf(0, pages.size - 1))
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
                    computeAdjacentChapters(mangaId, chapterUrl, source)
                    beginSession(mangaId, chapterUrl)
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
        _state.update { it.copy(currentPage = page) }
        // Save progress & mark read at last page
        viewModelScope.launch {
            val chNum = st.chapterUrl.substringAfterLast("/").replace("[^0-9.]".toRegex(), "")
                .toFloatOrNull() ?: return@launch
            trackReadingTime()
            if (!st.incognitoMode) {
                libraryRepo.saveReadingProgress(st.mangaId, chNum, page, st.totalPages)
            }
            if (st.smartPrefetchEnabled && st.totalPages > 0 && page >= (st.totalPages / 2)) {
                prefetchNextChapterIfNeeded(st)
            }
            if (page >= st.totalPages - 1) {
                if (!st.incognitoMode) {
                    libraryRepo.markChapterRead(st.mangaId, chNum)
                    scheduleAutoCleanupIfNeeded(st.mangaId, st.chapterUrl)
                }
                widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
            }
        }
    }

    fun toggleControls() = _state.update { it.copy(showControls = !it.showControls) }
    fun setReaderMode(mode: ReaderMode) {
        _state.update { it.copy(readerMode = mode) }
        viewModelScope.launch { settingsRepo.updateReaderMode(mode) }
    }

    fun downloadCurrentChapter() {
        val st = _state.value
        if (st.pages.isEmpty() || st.downloadInProgress) return
        val taskId = "${st.mangaId}_${st.chapterUrl.hashCode()}"
        viewModelScope.launch {
            val referer = st.pages.firstOrNull()?.headers?.get("Referer")
                ?.takeIf { it.isNotBlank() }
                ?: st.chapterUrl
            _state.update { it.copy(downloadInProgress = true, downloadProgress = 0f, downloadMessage = "بدء التنزيل...", activeDownloadTaskId = taskId) }
            downloadQueueManager.enqueueAndRun(
                taskId = taskId,
                mangaId = st.mangaId,
                mangaTitle = st.mangaId.substringAfter("_").ifBlank { st.mangaId },
                chapterUrl = st.chapterUrl,
                chapterTitle = null,
                pages = st.pages,
                wifiOnly = st.downloadOnWifiOnly,
                referer = referer
            )
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

    fun setBrightness(value: Float) = viewModelScope.launch {
        settingsRepo.updateBrightness(value)
    }

    fun setImageFilter(filter: ReaderImageFilter) = viewModelScope.launch {
        settingsRepo.updateImageFilter(filter)
    }

    fun setIncognito(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateIncognitoMode(enabled)
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

    override fun onCleared() {
        super.onCleared()
        finishSessionAsync()
    }

    private fun beginSession(mangaId: String, chapterUrl: String) {
        activeSessionKey = "$mangaId|$chapterUrl"
        sessionCheckpointAt = System.currentTimeMillis()
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

    private suspend fun computeAdjacentChapters(mangaId: String, chapterUrl: String, source: MangaSource) {
        val detail = cacheDao.get(mangaId)?.toDetail(source)
            ?: mangaRepo.getMangaDetail(mangaId.substringAfter("_"), source).getOrNull()
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

    private suspend fun scheduleAutoCleanupIfNeeded(mangaId: String, chapterUrl: String) {
        val settings = settingsRepo.getAppSettings().first()
        if (!settings.autoCleanupReadDownloads) return
        val targetDir = downloadQueueManager.getDownloadedChapterDir(mangaId, chapterUrl) ?: return
        val request = OneTimeWorkRequestBuilder<ChapterCleanupWorker>()
            .setInitialDelay(settings.cleanupAfterHours.toLong(), TimeUnit.HOURS)
            .setInputData(
                Data.Builder()
                    .putString(ChapterCleanupWorker.KEY_MANGA_ID, mangaId)
                    .putString(ChapterCleanupWorker.KEY_TARGET_DIR, targetDir)
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
        }
        sessionCheckpointAt = now
    }

    private fun finishSessionAsync() {
        if (_state.value.incognitoMode) {
            sessionCheckpointAt = null
            activeSessionKey = null
            return
        }
        val checkpoint = sessionCheckpointAt ?: return
        val now = System.currentTimeMillis()
        val delta = (now - checkpoint).coerceIn(0L, 2 * 60_000L)
        sessionCheckpointAt = null
        activeSessionKey = null
        if (delta >= 1_000L) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                readingStatsStore.addReadingTime(delta)
                widgetShortcutCoordinator.refreshWidgets()
            }
        }
    }
}
