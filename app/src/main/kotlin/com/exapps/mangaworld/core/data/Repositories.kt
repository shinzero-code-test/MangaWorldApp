package com.exapps.mangaworld.core.data

import androidx.paging.*
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.*
import com.exapps.mangaworld.core.data.local.entity.*
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// ─── Chapter serialisation helpers ───────────────────────────────────────────

private fun Chapter.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("mangaId", mangaId); put("number", number.toDouble())
    put("title", title ?: JSONObject.NULL)
    put("url", url)
    put("date", date ?: JSONObject.NULL)
    put("dateText", dateText ?: JSONObject.NULL)
}

private fun JSONObject.toChapter(): Chapter? = runCatching {
    Chapter(
        id = getString("id"),
        mangaId = getString("mangaId"),
        number = getDouble("number").toFloat(),
        title = optString("title").ifBlank { null },
        url = getString("url"),
        date = if (isNull("date")) null else getLong("date"),
        dateText = optString("dateText").ifBlank { null }
    )
}.getOrNull()

private fun List<Chapter>.toJsonString(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

private fun String.toChapterList(): List<Chapter> = runCatching {
    val arr = JSONArray(this)
    (0 until arr.length()).mapNotNull { arr.getJSONObject(it).toChapter() }
}.getOrDefault(emptyList())

// ─── Cache entity conversions ─────────────────────────────────────────────────

internal fun MangaCacheEntity.toDetail(source: MangaSource) = MangaDetail(
    id = mangaId, slug = slug, title = title, coverUrl = coverUrl,
    source = source, description = description,
    genres = runCatching {
        val a = JSONArray(genresJson); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()),
    status = runCatching { MangaStatus.valueOf(statusStr) }.getOrDefault(MangaStatus.UNKNOWN),
    type = runCatching { MangaType.valueOf(typeStr) }.getOrDefault(MangaType.UNKNOWN),
    totalChapters = totalChapters ?: 0,
    url = url, rating = rating,
    chapters = chaptersJson.toChapterList()
)

internal fun MangaDetail.toCacheEntity() = MangaCacheEntity(
    mangaId = id, slug = slug, title = title, coverUrl = coverUrl,
    sourceId = source.id, description = description,
    totalChapters = totalChapters, url = url,
    genresJson = JSONArray(genres).toString(),
    statusStr = status.name, typeStr = type.name, rating = rating,
    chaptersJson = chapters.toJsonString()
)

// ─── MangaRepository ──────────────────────────────────────────────────────────

@Singleton
class MangaRepositoryImpl @Inject constructor(
    private val scrapers: Map<String, @JvmSuppressWildcards MangaScraper>,
    private val cacheDao: MangaCacheDao
) : MangaRepository {

    private fun scraper(source: MangaSource): MangaScraper =
        scrapers[source.id] ?: error("No scraper for ${source.id}")

    override suspend fun getHomeData(source: MangaSource) =
        scraper(source).getHomeData()

    override fun searchManga(filters: SearchFilters): Flow<PagingData<MangaItem>> = Pager(
        config = PagingConfig(pageSize = 24, enablePlaceholders = false)
    ) {
        MangaPagingSource(scrapers, filters)
    }.flow

    override suspend fun getMangaByGenre(genre: String, source: MangaSource, page: Int) =
        scraper(source).getMangaByGenre(genre, page)

    /**
     * Cache-first strategy:
     *  1. Return cached detail immediately (with cached chapters) if available.
     *  2. Fetch fresh from network and update cache.
     *  3. If network returns empty chapters but cache had some, keep cached chapters.
     */
    override suspend fun getMangaDetail(slug: String, source: MangaSource): Result<MangaDetail> {
        val mangaId = "${source.id}_$slug"

        // Load cached version (with chapters) to return as immediate fallback
        val cached = runCatching { cacheDao.get(mangaId)?.toDetail(source) }.getOrNull()

        // Fetch fresh from scraper
        val networkResult = scraper(source).getMangaDetail(slug)

        return networkResult
            .map { fresh ->
                // If network returned empty chapters but cache has them, prefer cache
                val chapters = if (fresh.chapters.isEmpty() && (cached?.chapters?.isNotEmpty() == true)) {
                    cached.chapters
                } else {
                    fresh.chapters
                }
                fresh.copy(chapters = chapters)
            }
            .onSuccess { detail ->
                // Update cache with latest data (including chapters)
                runCatching { cacheDao.insert(detail.toCacheEntity()) }
            }
            .recoverCatching { e ->
                // Network failed — return cache if available, else re-throw
                cached ?: throw e
            }
    }

    override suspend fun getChapterPages(mangaSlug: String, chapterUrl: String, source: MangaSource) =
        scraper(source).getChapterPages(chapterUrl)

    override suspend fun getPopularManga(source: MangaSource) =
        scraper(source).getPopularManga()

    override suspend fun getGenres(source: MangaSource?, enabledSourceIds: Set<String>?): List<String> {
        val allowed = enabledSourceIds ?: MangaSource.entries.map { it.id }.toSet()
        val sources = if (source != null) listOf(source) else MangaSource.values().toList()
        return sources.flatMap { s ->
            if (s.id !in allowed) return@flatMap emptyList()
            val sc = scrapers[s.id] ?: return@flatMap emptyList()
            sc.getGenres().getOrDefault(emptyList())
        }.distinct().sorted()
    }
}

// ─── PagingSource ─────────────────────────────────────────────────────────────

class MangaPagingSource(
    private val scrapers: Map<String, MangaScraper>,
    private val filters: SearchFilters
) : PagingSource<Int, MangaItem>() {

    private fun normalize(text: String): String = text.lowercase()
        .replace("[\\u064B-\\u065F]".toRegex(), "")
        .replace("[^\\p{L}\\p{Nd}]".toRegex(), "")

    private fun rank(item: MangaItem, query: String): Int {
        if (query.isBlank()) return 0
        val q = normalize(query)
        val title = normalize(item.title)
        return when {
            title == q -> 0
            title.startsWith(q) -> 1
            title.contains(q) -> 2
            else -> 3
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MangaItem> {
        val page = params.key ?: 1
        return try {
            val allowedSourceIds = filters.enabledSourceIds
            val sources = if (filters.source != null) {
                listOfNotNull(filters.source.takeIf { it.id in allowedSourceIds })
            } else {
                MangaSource.values().filter { it.id in allowedSourceIds }
            }

            // getPopularManga has no pagination — only load page 1
            if (filters.query.isEmpty() && filters.genre == null && page > 1) {
                return LoadResult.Page(data = emptyList(), prevKey = page - 1, nextKey = null)
            }

            // Run all source queries in parallel, tolerate CF errors per-source
            val rawResults: List<MangaItem> = coroutineScope {
                val deferred: List<kotlinx.coroutines.Deferred<List<MangaItem>>> = sources.map { source ->
                    async {
                        val scraper = scrapers[source.id] ?: return@async emptyList<MangaItem>()
                        val result = when {
                            filters.query.isNotEmpty() -> scraper.searchManga(filters.query, page)
                            filters.genre != null -> scraper.getMangaByGenre(filters.genre, page)
                            else -> scraper.getPopularManga()
                        }

                        result.getOrElse { e ->
                            if (filters.source != null) throw e
                            emptyList()
                        }
                    }
                }
                deferred.awaitAll().flatMap { it }
            }.distinctBy { it.id }

            var filtered = rawResults
            if (filters.blockedKeywords.isNotEmpty()) {
                filtered = filtered.filterNot { it.isBlockedBy(filters.blockedKeywords) }
            }
            if (filters.status != null) filtered = filtered.filter { it.status == filters.status }
            if (filters.type != null)   filtered = filtered.filter { it.type == filters.type }

            val sorted = when (filters.sortBy) {
                SortBy.RATING     -> filtered.sortedByDescending { it.rating ?: 0f }
                SortBy.POPULARITY -> filtered.sortedByDescending { it.latestChapter ?: 0 }
                SortBy.OLDEST     -> filtered.sortedBy { it.title.lowercase() }
                SortBy.LATEST     -> filtered.sortedWith(
                    compareBy<MangaItem> { rank(it, filters.query) }
                        .thenBy { it.title.lowercase() }
                )
            }

            LoadResult.Page(
                data = sorted,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (sorted.size < 20) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MangaItem>) =
        state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(1) }
}

// ─── LibraryRepository ────────────────────────────────────────────────────────

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readChapterDao: ReadChapterDao,
    private val progressDao: ReadingProgressDao,
    private val readerAnnotationDao: ReaderAnnotationDao
) : LibraryRepository {

    override fun getFavorites(): Flow<List<FavoriteManga>> =
        favoriteDao.getAllFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun addFavorite(manga: FavoriteManga) = favoriteDao.insert(manga.toEntity())
    override suspend fun removeFavorite(mangaId: String) = favoriteDao.delete(mangaId)
    override suspend fun isFavorite(mangaId: String) = favoriteDao.isFavorite(mangaId)
    override fun isFavoriteFlow(mangaId: String): Flow<Boolean> = favoriteDao.isFavoriteFlow(mangaId)

    override fun getReadingHistory(): Flow<List<ReadingHistoryItem>> =
        historyDao.getAllHistory().map { list -> list.map { it.toDomain() } }

    override suspend fun updateReadingHistory(
        mangaId: String, slug: String, title: String, coverUrl: String,
        source: MangaSource, chapterNumber: Float, chapterUrl: String, totalChapters: Int
    ) {
        val existing = historyDao.getByMangaId(mangaId)
        val readCount = readChapterDao.getReadChapters(mangaId).first().size
        historyDao.insertOrUpdate(
            ReadingHistoryEntity(
                mangaId = mangaId, slug = slug, title = title, coverUrl = coverUrl,
                sourceId = source.id, lastChapterNumber = chapterNumber,
                lastChapterUrl = chapterUrl,
                lastReadAt = System.currentTimeMillis(),
                readChapters = readCount,
                totalChapters = if (totalChapters > 0) totalChapters else existing?.totalChapters ?: 0
            )
        )
        favoriteDao.getById(mangaId)?.let { favorite ->
            favoriteDao.updateProgress(
                mangaId = mangaId,
                read = readCount,
                total = if (totalChapters > 0) totalChapters else favorite.totalChapters
            )
        }
    }

    override suspend fun clearHistory() = historyDao.clearAll()
    override suspend fun removeFromHistory(mangaId: String) = historyDao.delete(mangaId)

    override suspend fun markChapterRead(mangaId: String, chapterNumber: Float) {
        readChapterDao.markRead(ReadChapterEntity(mangaId, chapterNumber))
        syncFavoriteProgress(mangaId)
    }

    override suspend fun markChapterUnread(mangaId: String, chapterNumber: Float) {
        readChapterDao.markUnread(mangaId, chapterNumber)
        syncFavoriteProgress(mangaId)
    }

    override suspend fun isChapterRead(mangaId: String, chapterNumber: Float): Boolean =
        readChapterDao.isRead(mangaId, chapterNumber)

    override fun getReadChapters(mangaId: String): Flow<Set<Float>> =
        readChapterDao.getReadChapters(mangaId).map { it.toSet() }

    override suspend fun saveReadingProgress(mangaId: String, chapterNumber: Float, page: Int, totalPages: Int) =
        progressDao.save(ReadingProgressEntity(mangaId, chapterNumber, page, totalPages))

    override suspend fun getReadingProgress(mangaId: String, chapterNumber: Float): Pair<Int, Int> {
        val p = progressDao.get(mangaId, chapterNumber)
        return Pair(p?.currentPage ?: 0, p?.totalPages ?: 0)
    }

    override suspend fun getReadingProgressMap(mangaId: String): Map<Float, Pair<Int, Int>> =
        progressDao.getAllForManga(mangaId).associate { it.chapterNumber to Pair(it.currentPage, it.totalPages) }

    override fun observeReaderAnnotations(mangaId: String, chapterUrl: String): Flow<List<ReaderPageAnnotation>> =
        readerAnnotationDao.observeChapterAnnotations(mangaId, chapterUrl)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun togglePageBookmark(mangaId: String, chapterUrl: String, pageIndex: Int) {
        val current = readerAnnotationDao.get(mangaId, chapterUrl, pageIndex)
        val nextBookmark = !(current?.isBookmarked ?: false)
        val nextNote = current?.note.orEmpty()
        if (!nextBookmark && nextNote.isBlank()) {
            readerAnnotationDao.delete(mangaId, chapterUrl, pageIndex)
        } else {
            readerAnnotationDao.upsert(
                ReaderAnnotationEntity(
                    mangaId = mangaId,
                    chapterUrl = chapterUrl,
                    pageIndex = pageIndex,
                    note = nextNote,
                    isBookmarked = nextBookmark,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun savePageNote(mangaId: String, chapterUrl: String, pageIndex: Int, note: String) {
        val current = readerAnnotationDao.get(mangaId, chapterUrl, pageIndex)
        val normalized = note.trim()
        val keepBookmark = current?.isBookmarked ?: false
        if (normalized.isBlank() && !keepBookmark) {
            readerAnnotationDao.delete(mangaId, chapterUrl, pageIndex)
        } else {
            readerAnnotationDao.upsert(
                ReaderAnnotationEntity(
                    mangaId = mangaId,
                    chapterUrl = chapterUrl,
                    pageIndex = pageIndex,
                    note = normalized,
                    isBookmarked = keepBookmark,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun getPageAnnotation(mangaId: String, chapterUrl: String, pageIndex: Int): ReaderPageAnnotation? =
        readerAnnotationDao.get(mangaId, chapterUrl, pageIndex)?.toDomain()

    private suspend fun syncFavoriteProgress(mangaId: String) {
        val favorite = favoriteDao.getById(mangaId) ?: return
        val readCount = readChapterDao.getReadChapters(mangaId).first().size
        val total = historyDao.getByMangaId(mangaId)?.totalChapters?.takeIf { it > 0 } ?: favorite.totalChapters
        favoriteDao.updateProgress(mangaId, readCount, total)
    }
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: AppPreferences
) : SettingsRepository {
    override fun getAppSettings() = prefs.appSettings
    override suspend fun updateTheme(theme: AppTheme) { prefs.setTheme(theme) }
    override suspend fun setOnboardingCompleted(completed: Boolean) { prefs.setOnboardingDone(completed) }
    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) { prefs.setDownloadWifiOnly(enabled) }
    override suspend fun setAutoDownloadNewChapters(enabled: Boolean) { prefs.setAutoDownload(enabled) }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { prefs.setNotifications(enabled) }
    override suspend fun toggleSource(sourceId: String, enabled: Boolean) { prefs.toggleSource(sourceId, enabled) }
    override suspend fun setDynamicColors(enabled: Boolean) { prefs.setDynamicColors(enabled) }
    override suspend fun setBiometricLock(enabled: Boolean) { prefs.setBiometricLock(enabled) }
    override suspend fun setSecureReader(enabled: Boolean) { prefs.setSecureReader(enabled) }
    override suspend fun setAutoCleanupReadDownloads(enabled: Boolean) { prefs.setAutoCleanup(enabled) }
    override suspend fun setCleanupAfterHours(hours: Int) { prefs.setCleanupHours(hours) }
    override suspend fun setImageCacheLimitMb(limitMb: Int) { prefs.setImageCacheLimitMb(limitMb) }
    override suspend fun setContentBlacklist(values: Set<String>) { prefs.setContentBlacklist(values) }
    override fun getReaderSettings() = prefs.readerSettings
    override suspend fun updateReaderMode(mode: ReaderMode) { prefs.setReaderMode(mode) }
    override suspend fun updateBrightness(brightness: Float) { prefs.setBrightness(brightness) }
    override suspend fun updateKeepScreenOn(enabled: Boolean) { prefs.setKeepScreen(enabled) }
    override suspend fun updateAutoWebtoon(enabled: Boolean) { prefs.setAutoWebtoon(enabled) }
    override suspend fun updateIncognitoMode(enabled: Boolean) { prefs.setIncognito(enabled) }
    override suspend fun updateSmartPrefetch(enabled: Boolean) { prefs.setSmartPrefetch(enabled) }
    override suspend fun updateReaderHaptics(enabled: Boolean) { prefs.setReaderHaptics(enabled) }
    override suspend fun updateImageFilter(filter: ReaderImageFilter) { prefs.setImageFilter(filter) }
    override fun getCookies(domain: String) = prefs.getCookies(domain)
    override suspend fun saveCookies(domain: String, cookies: String) { prefs.saveCookies(domain, cookies) }
    override suspend fun clearCookies(domain: String) { prefs.clearCookies(domain) }
}
