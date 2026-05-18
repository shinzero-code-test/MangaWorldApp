package com.exapps.mangaworld.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    val downloadOnWifiOnly: Boolean = true
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val cacheDao: MangaCacheDao,
    private val readingStatsStore: ReadingStatsStore,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var currentSource: MangaSource = MangaSource.STARZ
    private var activeSessionKey: String? = null
    private var sessionCheckpointAt: Long? = null

    fun loadChapter(chapterUrl: String, mangaId: String, source: MangaSource) {
        if (_state.value.chapterUrl != chapterUrl) {
            finishSessionAsync()
        }
        currentSource = source
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
                    runCatching {
                        val cached = cacheDao.get(mangaId)
                        if (cached != null) {
                            libraryRepo.updateReadingHistory(
                                mangaId = mangaId, slug = cached.slug,
                                title = cached.title, coverUrl = cached.coverUrl,
                                source = MangaSource.fromId(cached.sourceId),
                                chapterNumber = chNum,
                                totalChapters = cached.totalChapters ?: 0
                            )
                        }
                    }
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
            libraryRepo.saveReadingProgress(st.mangaId, chNum, page, st.totalPages)
            if (page >= st.totalPages - 1) {
                libraryRepo.markChapterRead(st.mangaId, chNum)
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

    override fun onCleared() {
        super.onCleared()
        finishSessionAsync()
    }

    private fun beginSession(mangaId: String, chapterUrl: String) {
        activeSessionKey = "$mangaId|$chapterUrl"
        sessionCheckpointAt = System.currentTimeMillis()
    }

    private suspend fun trackReadingTime() {
        val checkpoint = sessionCheckpointAt ?: return
        val now = System.currentTimeMillis()
        val delta = (now - checkpoint).coerceIn(0L, 2 * 60_000L)
        if (delta >= 1_000L) {
            readingStatsStore.addReadingTime(delta)
        }
        sessionCheckpointAt = now
    }

    private fun finishSessionAsync() {
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
