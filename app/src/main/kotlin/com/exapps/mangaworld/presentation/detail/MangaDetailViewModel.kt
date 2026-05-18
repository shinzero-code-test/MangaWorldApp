package com.exapps.mangaworld.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import kotlinx.coroutines.flow.first
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val manga: MangaDetail? = null,
    val isFavorite: Boolean = false,
    val readChapters: Set<Float> = emptySet(),
    val downloadedChapters: Set<String> = emptySet(),
    val readingProgress: Map<Float, Pair<Int, Int>> = emptyMap(),
    val chaptersReversed: Boolean = true,
    val error: String? = null,
    val downloadingChapters: Set<Float> = emptySet(),
    val showDownloadDialog: Boolean = false,
    val cloudflareUrl: String? = null,
    val cloudfareDomain: String? = null
)

@HiltViewModel
class MangaDetailViewModel @Inject constructor(
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val downloadQueueManager: DownloadQueueManager
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var currentMangaId: String = ""
    private var currentSource: MangaSource = MangaSource.AZORA
    private var currentSlug: String = ""

    // One job for the network fetch, one for the ongoing observers
    private var loadJob: Job? = null
    private var observersJob: Job? = null

    fun load(slug: String, source: MangaSource) {
        // Skip if already loaded and nothing changed
        if (slug == currentSlug && source == currentSource
            && !_state.value.isLoading
            && _state.value.manga != null
            && _state.value.error == null
        ) return

        currentSlug = slug
        currentSource = source
        currentMangaId = "${source.id}_$slug"

        // Cancel previous work (including infinite collectors)
        observersJob?.cancel()
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            val hasData = _state.value.manga != null
            _state.update { it.copy(isLoading = !hasData, error = null) }

            mangaRepo.getMangaDetail(slug, source)
                .onSuccess { detail ->
                    // Preserve cached chapters when network returns empty
                    val chapters = if (detail.chapters.isEmpty()
                        && (_state.value.manga?.chapters?.isNotEmpty() == true)
                    ) _state.value.manga!!.chapters else detail.chapters

                    _state.update {
                        it.copy(isLoading = false, manga = detail.copy(chapters = chapters))
                    }

                    // Start all infinite-flow observers in a single cancellable job
                    observersJob?.cancel()
                    observersJob = viewModelScope.launch {
                        val mangaId = currentMangaId   // capture before any re-load

                        launch {
                            libraryRepo.isFavoriteFlow(mangaId)
                                .collect { fav -> _state.update { it.copy(isFavorite = fav) } }
                        }
                        launch {
                            libraryRepo.getReadChapters(mangaId)
                                .collect { read -> _state.update { it.copy(readChapters = read) } }
                        }
                        launch {
                            val progress = libraryRepo.getReadingProgressMap(mangaId)
                            _state.update { it.copy(readingProgress = progress) }
                        }
                        launch {
                            val downloaded = withContext(Dispatchers.IO) {
                                chapters.filter { ch ->
                                    downloadQueueManager.isChapterDownloaded(mangaId, ch.url)
                                }.map { it.url }.toSet()
                            }
                            _state.update { it.copy(downloadedChapters = downloaded) }
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = if (it.manga == null) (e.message ?: "خطأ في التحميل") else null,
                            cloudflareUrl = if (e is CloudflareChallengeException) e.targetUrl else null,
                            cloudfareDomain = if (e is CloudflareChallengeException) e.domain else null
                        )
                    }
                }
        }
    }

    // ─── Favourite ────────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val manga = _state.value.manga ?: return
        viewModelScope.launch {
            if (_state.value.isFavorite) {
                libraryRepo.removeFavorite(currentMangaId)
            } else {
                libraryRepo.addFavorite(
                    FavoriteManga(
                        mangaId = currentMangaId, slug = manga.slug,
                        title = manga.title, coverUrl = manga.coverUrl,
                        source = manga.source, totalChapters = manga.totalChapters
                    )
                )
            }
        }
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────

    fun toggleChaptersOrder() = _state.update { it.copy(chaptersReversed = !it.chaptersReversed) }

    fun sortedChapters(): List<Chapter> {
        val state = _state.value
        val manga = state.manga ?: return emptyList()
        val sorted = if (state.chaptersReversed)
            manga.chapters.sortedByDescending { it.number }
        else manga.chapters.sortedBy { it.number }

        return sorted.map { ch ->
            val (savedPage, savedTotal) = state.readingProgress[ch.number] ?: Pair(0, 0)
            ch.copy(
                isRead = state.readChapters.contains(ch.number),
                readPage = savedPage,
                totalPages = savedTotal,
                isDownloaded = state.downloadedChapters.contains(ch.url)
            )
        }
    }

    // ─── Download ─────────────────────────────────────────────────────────────

    // ─── Cloudflare ──────────────────────────────────────────────────────────────

    /** Called after the user solves the Cloudflare challenge in the WebView. */
    fun onCloudflareSolved(domain: String, cookies: String) {
        CookieCache.put(domain, cookies)
        viewModelScope.launch {
            settingsRepo.saveCookies(domain, cookies)
            delay(300)
            _state.update { it.copy(cloudflareUrl = null, cloudfareDomain = null, error = null) }
            load(currentSlug, currentSource)
        }
    }

        fun showDownloadDialog() = _state.update { it.copy(showDownloadDialog = true) }
    fun hideDownloadDialog() = _state.update { it.copy(showDownloadDialog = false) }

    /** Enqueue a single chapter for download. */
    fun downloadChapter(chapter: Chapter) {
        if (_state.value.downloadingChapters.contains(chapter.number)) return
        _state.update { it.copy(downloadingChapters = it.downloadingChapters + chapter.number) }

        viewModelScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    mangaRepo.getChapterPages(currentSlug, chapter.url, currentSource)
                        .getOrDefault(emptyList())
                }
                if (pages.isNotEmpty()) {
                    val wifiOnly = settingsRepo.getAppSettings().first().downloadOnWifiOnly
                    val manga = _state.value.manga
                    val srcReferer = pages.firstOrNull()?.headers?.get("Referer")
                        ?.takeIf { it.isNotBlank() }
                        ?: chapter.url
                    downloadQueueManager.enqueueAndRun(
                        taskId = "dl_${UUID.randomUUID()}",
                        mangaId = currentMangaId,
                        mangaTitle = manga?.title ?: currentSlug,
                        chapterUrl = chapter.url,
                        chapterTitle = chapter.title ?: "الفصل ${chapter.displayNumber}",
                        pages = pages,
                        wifiOnly = wifiOnly,
                        referer = srcReferer,
                        mangaMetadata = manga?.let { m ->
                            com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity(
                                mangaId = currentMangaId,
                                slug = m.slug,
                                title = m.title,
                                coverUrl = m.coverUrl,
                                sourceId = m.source.id,
                                totalChapters = m.totalChapters,
                                genresJson = org.json.JSONArray(m.genres).toString(),
                                statusStr = m.status.name,
                                typeStr = m.type.name,
                                description = m.description
                            )
                        }
                    )
                }
            } finally {
                _state.update { it.copy(downloadingChapters = it.downloadingChapters - chapter.number) }
                refreshDownloadedSet()
            }
        }
    }

    /** Enqueue a list of chapters for download (e.g. all unread). */
    fun downloadChapters(chapters: List<Chapter>) {
        chapters.forEach { downloadChapter(it) }
    }

    /** Download every chapter that isn't already downloaded. */
    fun downloadAllChapters() {
        val todo = sortedChapters().filter { !it.isDownloaded }
        downloadChapters(todo)
        hideDownloadDialog()
    }

    /** Download only chapters that are neither read nor already downloaded. */
    fun downloadUnreadChapters() {
        val todo = sortedChapters().filter { !it.isDownloaded && !it.isRead }
        downloadChapters(todo)
        hideDownloadDialog()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun refreshDownloadedSet() {
        val chapters = _state.value.manga?.chapters ?: return
        val mangaId = currentMangaId
        val downloaded = withContext(Dispatchers.IO) {
            chapters.filter { ch ->
                downloadQueueManager.isChapterDownloaded(mangaId, ch.url)
            }.map { it.url }.toSet()
        }
        _state.update { it.copy(downloadedChapters = downloaded) }
    }
}
