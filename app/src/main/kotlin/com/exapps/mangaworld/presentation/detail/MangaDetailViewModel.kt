package com.exapps.mangaworld.presentation.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.core.firebase.FirebaseTopicManager
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import kotlinx.coroutines.flow.first
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject

@Immutable
data class DetailUiState(
    val isLoading: Boolean = true,
    val manga: MangaDetail? = null,
    val otherSourceMatches: List<MangaItem> = emptyList(),
    val sourceComparisons: List<SourceComparison> = emptyList(),
    val showSourceComparison: Boolean = false,
    val isFavorite: Boolean = false,
    val readChapters: Set<Float> = emptySet(),
    val downloadedChapters: Set<String> = emptySet(),
    val readingProgress: Map<Float, Pair<Int, Int>> = emptyMap(),
    val chaptersReversed: Boolean = true,
    val error: String? = null,
    val downloadingChapters: Set<Float> = emptySet(),
    val showDownloadDialog: Boolean = false,
    val cloudflareUrl: String? = null,
    val cloudfareDomain: String? = null,
    val userLists: List<CustomUserList> = emptyList(),
    val showAddToListDialog: Boolean = false,
    val chapterSearchQuery: String = ""
)

@HiltViewModel
class MangaDetailViewModel @Inject constructor(
    private val mangaRepo: MangaRepository,
    private val libraryRepo: LibraryRepository,
    private val communityRepository: CommunityRepository,
    private val settingsRepo: SettingsRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val firebaseSyncManager: FirebaseSyncManager,
    private val firebaseTopicManager: FirebaseTopicManager,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val firebaseTelemetry: FirebaseTelemetry
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
        // For imported/downloaded manga, the slug IS the mangaId (starts with "imported_")
        currentMangaId = if (slug.startsWith("imported_")) slug else "${source.id}_$slug"

        // Cancel previous work (including infinite collectors)
        observersJob?.cancel()
        loadJob?.cancel()

        // All manga live on disk: imported manga is always local;
        // downloaded manga uses local metadata.json + chapter dirs until
        // the user explicitly refreshes from the online source.
        if (source.id == "imported" || slug.startsWith("imported_") || hasLocalData(currentMangaId)) {
            loadFromLocalDisk(slug, source)
            return
        }

        loadJob = viewModelScope.launch {
            val hasData = _state.value.manga != null
            _state.update { it.copy(isLoading = !hasData, error = null) }

            mangaRepo.getMangaDetail(slug, source)
                .onSuccess { detail ->
                    firebaseTelemetry.setActiveSource(detail.source.id)
                    // Preserve cached chapters when network returns empty
                    val chapters = if (detail.chapters.isEmpty()
                        && (_state.value.manga?.chapters?.isNotEmpty() == true)
                    ) _state.value.manga!!.chapters else detail.chapters

                    _state.update {
                        it.copy(isLoading = false, manga = detail.copy(chapters = chapters))
                    }
                    analyticsManager.logMangaViewed(
                        mangaId = currentMangaId,
                        sourceId = detail.source.id,
                        genres = detail.genres,
                        chapterCount = detail.totalChapters
                    )

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
                        launch {
                            val matches = loadOtherSourceMatches(detail)
                            _state.update { it.copy(otherSourceMatches = matches) }
                        }
                        launch {
                            communityRepository.observeUserLists().collect { lists ->
                                _state.update { it.copy(userLists = lists) }
                            }
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

    /** Check whether a manga directory with metadata.json exists on disk. */
    private fun hasLocalData(mangaId: String): Boolean {
        val dir = File(downloadQueueManager.getMangaDirPath(mangaId))
        return dir.exists() && File(dir, "metadata.json").exists()
    }

    /**
     * Load a manga from the local filesystem (metadata.json + chapter dirs).
     * Used for imported manga and downloaded manga that already exist on disk.
     */
    private fun loadFromLocalDisk(slug: String, source: MangaSource) {
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val mangaDirPath = downloadQueueManager.getMangaDirPath(currentMangaId)
                val mangaDir = File(mangaDirPath)
                if (!mangaDir.exists()) {
                    _state.update { it.copy(isLoading = false, error = "المجلد المحلي غير موجود") }
                    return@launch
                }

                // Read metadata.json
                val metadataFile = File(mangaDir, "metadata.json")
                val json = if (metadataFile.exists()) {
                    JSONObject(metadataFile.readText())
                } else JSONObject()

                val title = json.optString("title", slug)
                val description = json.optString("description", "")
                val genresRaw = json.optJSONArray("genres") ?: JSONArray()
                val genres = (0 until genresRaw.length()).map { genresRaw.getString(it) }
                val totalChaptersFromJson = json.optInt("totalChapters", 0)

                // Scan chapter directories
                val chapterDirs = mangaDir.listFiles()
                    ?.filter { it.isDirectory && File(it, ".completed").exists() }
                    ?.sortedBy { dir ->
                        dir.name.replace("[^0-9.]".toRegex(), "").toFloatOrNull() ?: 0f
                    }
                    ?: emptyList()

                val chapters = chapterDirs.mapIndexed { index, dir ->
                    val chNumber = dir.name.replace("[^0-9.]".toRegex(), "").toFloatOrNull()
                        ?: (index + 1).toFloat()
                    val pageCount = dir.listFiles()
                        ?.count { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                        ?: 0
                    Chapter(
                        id = "${currentMangaId}_${dir.name}",
                        mangaId = currentMangaId,
                        number = chNumber,
                        title = "الفصل ${chNumber}",
                        // chapterUrl = directory name; reader uses getLocalChapterPages(mangaId, chapterUrl)
                        url = dir.name,
                        totalPages = pageCount,
                        isDownloaded = true
                    )
                }

                val coverPath = File(mangaDir, "cover.jpg")
                val coverUrl = if (coverPath.exists()) coverPath.toURI().toString() else ""

                val mangaDetail = MangaDetail(
                    id = currentMangaId,
                    slug = slug,
                    title = title,
                    coverUrl = coverUrl,
                    source = source,
                    description = description,
                    genres = genres,
                    totalChapters = totalChaptersFromJson.coerceAtLeast(chapters.size),
                    chapters = chapters,
                    url = ""
                )

                _state.update { it.copy(isLoading = false, manga = mangaDetail) }

                // Start observers for favourite / read status / download state
                observersJob?.cancel()
                observersJob = viewModelScope.launch {
                    launch {
                        libraryRepo.isFavoriteFlow(currentMangaId)
                            .collect { fav -> _state.update { it.copy(isFavorite = fav) } }
                    }
                    launch {
                        libraryRepo.getReadChapters(currentMangaId)
                            .collect { read -> _state.update { it.copy(readChapters = read) } }
                    }
                    launch {
                        val downloaded = withContext(Dispatchers.IO) {
                            chapters.filter { ch ->
                                downloadQueueManager.isChapterDownloaded(currentMangaId, ch.url)
                            }.map { it.url }.toSet()
                        }
                        _state.update { it.copy(downloadedChapters = downloaded) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "خطأ في تحميل المانجا المحلية") }
            }
        }
    }

    // ─── Favourite ────────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val manga = _state.value.manga ?: return
        viewModelScope.launch {
            if (_state.value.isFavorite) {
                libraryRepo.removeFavorite(currentMangaId)
                runCatching { firebaseTopicManager.unsubscribeFromManga(currentMangaId) }
            } else {
                libraryRepo.addFavorite(
                    FavoriteManga(
                        mangaId = currentMangaId, slug = manga.slug,
                        title = manga.title, coverUrl = manga.coverUrl,
                        source = manga.source, totalChapters = manga.totalChapters
                    )
                )
                runCatching { firebaseTopicManager.subscribeToManga(currentMangaId) }
            }
            runCatching { firebaseSyncManager.pushLocalSnapshot() }
            widgetShortcutCoordinator.refreshWidgets()
        }
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────

    fun toggleChaptersOrder() = _state.update { it.copy(chaptersReversed = !it.chaptersReversed) }

    fun showAddToListDialog() = _state.update { it.copy(showAddToListDialog = true) }
    fun hideAddToListDialog() = _state.update { it.copy(showAddToListDialog = false) }

    fun addCurrentMangaToList(listId: String) {
        val manga = _state.value.manga ?: return
        viewModelScope.launch {
            runCatching {
                communityRepository.addMangaToList(
                    listId,
                    CustomUserListItem(
                        mangaId = currentMangaId,
                        sourceId = manga.source.id,
                        slug = manga.slug,
                        title = manga.title,
                        coverUrl = manga.coverUrl
                    )
                )
            }
            _state.update { it.copy(showAddToListDialog = false) }
        }
    }

    // ─── Source Comparison ─────────────────────────────────────────────────────

    fun showSourceComparison() {
        val manga = _state.value.manga ?: return
        _state.update { it.copy(showSourceComparison = true) }
        loadSourceComparisons(manga)
    }

    fun hideSourceComparison() = _state.update { it.copy(showSourceComparison = false) }

    fun switchSource(source: MangaSource, slug: String) {
        _state.update { it.copy(showSourceComparison = false) }
        load(slug, source)
    }

    private fun loadSourceComparisons(manga: MangaDetail) {
        viewModelScope.launch {
            val comparisons = MangaSource.entries
                .filter { it != currentSource }
                .map { source ->
                    SourceComparison(source = source, match = null, isLoading = true)
                }
            _state.update { it.copy(sourceComparisons = comparisons) }

            val results = MangaSource.entries
                .filter { it != currentSource }
                .map { source ->
                    async {
                        try {
                            val result = mangaRepo.searchMangaDirect(manga.title, source)
                            val match = result.getOrNull()?.firstOrNull { item ->
                                normalizeTitle(item.title).contains(normalizeTitle(manga.title)) ||
                                normalizeTitle(manga.title).contains(normalizeTitle(item.title))
                            }
                            SourceComparison(
                                source = source,
                                match = match,
                                chapterCount = match?.let { detail ->
                                    mangaRepo.getMangaDetail(detail.slug, source).getOrNull()?.totalChapters ?: 0
                                } ?: 0
                            )
                        } catch (e: Exception) {
                            SourceComparison(
                                source = source,
                                match = null,
                                error = e.message ?: "خطأ غير معروف"
                            )
                        }
                    }
                }.awaitAll()

            _state.update { it.copy(sourceComparisons = results) }
        }
    }

    private fun normalizeTitle(value: String): String = value.lowercase()
        .replace("[\\u064B-\\u065F]".toRegex(), "")
        .replace("[^\\p{L}\\p{Nd}]".toRegex(), "")

    private suspend fun loadOtherSourceMatches(detail: MangaDetail): List<MangaItem> = coroutineScope {
        val title = detail.title.trim()
        if (title.length < 2) return@coroutineScope emptyList()
        val normalizedTarget = normalizeTitle(title)
        val results = MangaSource.entries.filter { it != detail.source }.map { source ->
            async {
                mangaRepo.searchMangaDirect(title, source).getOrDefault(emptyList())
                    .filter { normalizeTitle(it.title) == normalizedTarget || normalizeTitle(it.title).contains(normalizedTarget) || normalizedTarget.contains(normalizeTitle(it.title)) }
                    .firstOrNull()
            }
        }.awaitAll().filterNotNull()
        results.distinctBy { it.source.id }.take(5)
    }

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
                    val m = _state.value.manga
                    val srcReferer = pages.firstOrNull()?.headers?.get("Referer")
                        ?.takeIf { it.isNotBlank() }
                        ?: chapter.url
                    // Always create the entity — even if manga detail hasn't loaded yet,
                    // the Local Storage screen needs a DB row to display the download.
                    val metadata = com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity(
                        mangaId = currentMangaId,
                        slug = m?.slug ?: currentSlug,
                        title = m?.title ?: currentSlug,
                        coverUrl = m?.coverUrl ?: "",
                        sourceId = m?.source?.id ?: currentSource.id,
                        totalChapters = m?.totalChapters ?: 0,
                        genresJson = m?.let { org.json.JSONArray(it.genres).toString() } ?: "[]",
                        statusStr = m?.status?.name ?: "UNKNOWN",
                        typeStr = m?.type?.name ?: "UNKNOWN",
                        description = m?.description ?: ""
                    )
                    downloadQueueManager.enqueueAndRun(
                        taskId = "dl_${UUID.randomUUID()}",
                        mangaId = currentMangaId,
                        mangaTitle = m?.title ?: currentSlug,
                        chapterUrl = chapter.url,
                        chapterTitle = chapter.title ?: "الفصل ${chapter.displayNumber}",
                        pages = pages,
                        wifiOnly = wifiOnly,
                        referer = srcReferer,
                        mangaMetadata = metadata
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

    // ─── Chapter Search ─────────────────────────────────────────────────────

    fun updateChapterSearchQuery(query: String) {
        _state.update { it.copy(chapterSearchQuery = query) }
    }

    fun getFilteredChapters(): List<Chapter> {
        val chapters = sortedChapters()
        val query = _state.value.chapterSearchQuery.trim()
        if (query.isEmpty()) return chapters
        return chapters.filter { ch ->
            ch.displayNumber.contains(query, ignoreCase = true) ||
            (ch.title?.contains(query, ignoreCase = true) == true) ||
            ch.number.toString().contains(query)
        }
    }

    // ─── Mark Read/Unread ───────────────────────────────────────────────────

    fun markChapterAsRead(chapter: Chapter) {
        val mangaId = currentMangaId
        viewModelScope.launch {
            libraryRepo.markChapterRead(mangaId, chapter.number)
            _state.update { it.copy(readChapters = it.readChapters + chapter.number) }
        }
    }

    fun markChapterAsUnread(chapter: Chapter) {
        val mangaId = currentMangaId
        viewModelScope.launch {
            libraryRepo.markChapterUnread(mangaId, chapter.number)
            _state.update { it.copy(readChapters = it.readChapters - chapter.number) }
        }
    }

    fun markAllChaptersAsRead() {
        val mangaId = currentMangaId
        val chapters = _state.value.manga?.chapters ?: return
        viewModelScope.launch {
            for (ch in chapters) {
                libraryRepo.markChapterRead(mangaId, ch.number)
            }
            _state.update { it.copy(readChapters = chapters.map { it.number }.toSet()) }
        }
    }

    fun markAllChaptersAsUnread() {
        val mangaId = currentMangaId
        val chapters = _state.value.manga?.chapters ?: return
        viewModelScope.launch {
            for (ch in chapters) {
                libraryRepo.markChapterUnread(mangaId, ch.number)
            }
            _state.update { it.copy(readChapters = emptySet()) }
        }
    }

    fun toggleChapterReadStatus(chapter: Chapter) {
        if (_state.value.readChapters.contains(chapter.number)) {
            markChapterAsUnread(chapter)
        } else {
            markChapterAsRead(chapter)
        }
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
