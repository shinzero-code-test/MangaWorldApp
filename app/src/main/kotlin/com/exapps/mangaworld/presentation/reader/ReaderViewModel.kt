package com.exapps.mangaworld.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
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
    private val downloadQueueManager: DownloadQueueManager,
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.getReaderSettings().collect { settings ->
                _state.update {
                    it.copy(readerMode = settings.mode, brightness = settings.brightness)
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.getAppSettings().collect { settings ->
                _state.update {
                    it.copy(downloadOnWifiOnly = settings.downloadOnWifiOnly)
                }
            }
        }
        viewModelScope.launch {
            downloadQueueManager.observeTasks().collect { tasks ->
                val currentId = _state.value.activeDownloadTaskId ?: return@collect
                val task = tasks.firstOrNull { it.id == currentId } ?: return@collect
                _state.update {
                    it.copy(
                        downloadInProgress = task.status == "running" || task.status == "queued",
                        downloadProgress = task.progress,
                        downloadMessage = when (task.status) {
                            "queued" -> "في انتظار التنزيل..."
                            "running" -> "يتم التنزيل ${task.downloadedPages}/${task.totalPages}"
                            "completed" -> "اكتمل التنزيل"
                            "failed" -> "فشل التنزيل: ${task.errorMessage ?: ""}"
                            "cancelled" -> "تم إلغاء التنزيل"
                            else -> it.downloadMessage
                        }
                    )
                }
            }
        }
    }

    fun loadChapter(chapterUrl: String, mangaId: String, source: MangaSource) {
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
                }
                .onFailure { e ->
                    val msg = if (e is CloudflareChallengeException) {
                        "CLOUDFLARE_REQUIRED|${e.domain}|${e.targetUrl}"
                    } else e.message
                    _state.update { it.copy(isLoading = false, error = msg) }
                }
        }
    }

    fun onPageChanged(page: Int) {
        val st = _state.value
        _state.update { it.copy(currentPage = page) }
        // Save progress & mark read at last page
        viewModelScope.launch {
            val chNum = st.chapterUrl.substringAfterLast("/").replace("[^0-9.]".toRegex(), "")
                .toFloatOrNull() ?: return@launch
            libraryRepo.saveReadingProgress(st.mangaId, chNum, page, st.totalPages)
            if (page >= st.totalPages - 1) {
                libraryRepo.markChapterRead(st.mangaId, chNum)
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
            _state.update { it.copy(downloadInProgress = true, downloadProgress = 0f, downloadMessage = "بدء التنزيل...", activeDownloadTaskId = taskId) }
            downloadQueueManager.enqueueAndRun(
                taskId = taskId,
                mangaId = st.mangaId,
                mangaTitle = st.mangaId.substringAfter("_").ifBlank { st.mangaId },
                chapterUrl = st.chapterUrl,
                chapterTitle = null,
                pages = st.pages,
                wifiOnly = st.downloadOnWifiOnly
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
}
