package com.exapps.mangaworld.core.data

import androidx.paging.*
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
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
    put("coverUrl", coverUrl)
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
        coverUrl = optString("coverUrl"),
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

internal fun MangaCacheEntity.toDetail(source: MangaSource): MangaDetail {
    val parsedGenres = runCatching {
        val a = JSONArray(genresJson); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
    return MangaDetail(
        id = mangaId, slug = slug, title = title, coverUrl = coverUrl,
        source = source, description = description,
        genres = parsedGenres,
        tags = parsedGenres,
        status = runCatching { MangaStatus.valueOf(statusStr) }.getOrDefault(MangaStatus.UNKNOWN),
        type = runCatching { MangaType.valueOf(typeStr) }.getOrDefault(MangaType.UNKNOWN),
        totalChapters = totalChapters ?: 0,
        url = url, rating = rating,
        chapters = chaptersJson.toChapterList()
    )
}

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
    private val cacheDao: MangaCacheDao,
    private val favoriteDao: FavoriteDao,
    private val firebaseTelemetry: FirebaseTelemetry,
    private val recommendationEngine: RecommendationEngine
) : MangaRepository {

    private fun scraper(source: MangaSource): MangaScraper =
        scrapers[source.id] ?: error("No scraper for ${source.id}. This source does not support online operations.")

    override suspend fun getHomeData(source: MangaSource) =
        runCatching {
            if (source.id == "local") return@runCatching com.exapps.mangaworld.domain.model.HomeData()
            firebaseTelemetry.traceSuspend("home_${source.id}") { scraper(source).getHomeData().getOrThrow() }
        }

    override fun searchManga(filters: SearchFilters): Flow<PagingData<MangaItem>> = Pager(
        config = PagingConfig(pageSize = 24, enablePlaceholders = false)
    ) {
        MangaPagingSource(scrapers, filters)
    }.flow

    override suspend fun searchMangaDirect(query: String, source: MangaSource, page: Int): Result<List<MangaItem>> {
        if (source.id == "local") return Result.success(emptyList())
        return scraper(source).searchManga(query, page)
    }

    override suspend fun browseMangaDirect(
        source: MangaSource, page: Int,
        genre: String?, status: MangaStatus?,
        type: MangaType?, sortBy: SortBy
    ): Result<List<MangaItem>> {
        if (source.id == "local") return Result.success(emptyList())
        return scraper(source).browseManga(page, genre, status, type, sortBy)
    }

    override suspend fun getMangaByGenre(genre: String, source: MangaSource, page: Int): Result<List<MangaItem>> {
        if (source.id == "local") return Result.success(emptyList())
        return scraper(source).getMangaByGenre(genre, page)
    }

    /**
     * Cache-first strategy:
     *  1. Return cached detail immediately (with cached chapters) if available.
     *  2. Fetch fresh from network and update cache.
     *  3. If network returns empty chapters but cache had some, keep cached chapters.
     */
    override suspend fun getMangaDetail(slug: String, source: MangaSource): Result<MangaDetail> {
        if (source.id == "local") return Result.failure(IllegalStateException("Cannot fetch detail for local manga"))
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
                firebaseTelemetry.logScraperFailure(source.id, "detail", e)
                // Network failed — return cache if available, else re-throw
                cached ?: throw e
            }
    }

    override suspend fun getChapterPages(mangaSlug: String, chapterUrl: String, source: MangaSource): Result<List<ChapterPage>> {
        if (source.id == "local") return Result.success(emptyList())
        return scraper(source).getChapterPages(chapterUrl)
    }

    override suspend fun getPopularManga(source: MangaSource): Result<List<MangaItem>> {
        if (source.id == "local") return Result.success(emptyList())
        return scraper(source).getPopularManga()
    }

    override suspend fun getSuggestedManga(candidates: List<MangaItem>, limit: Int): List<MangaItem> {
        return recommendationEngine.getSmartRecommendations(candidates, limit)
    }

    override suspend fun getGenres(source: MangaSource?, enabledSourceIds: Set<String>?): List<String> {
        val allowed = enabledSourceIds ?: MangaSource.entries.map { it.id }.toSet()
        val sources = if (source != null) listOf(source) else MangaSource.entries.toList()
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

    private val needsLocalFiltering: Boolean
        get() = filters.query.isEmpty() && (filters.status != null || filters.type != null)

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
                MangaSource.entries.filter { it.id in allowedSourceIds }
            }

            // Run all source queries in parallel, tolerate CF errors per-source
            val rawResults: List<MangaItem> = coroutineScope {
                val deferred: List<kotlinx.coroutines.Deferred<List<MangaItem>>> = sources.map { source ->
                    async {
                        val scraper = scrapers[source.id] ?: return@async emptyList<MangaItem>()
                        val fetched = if (needsLocalFiltering) {
                            val aggregate = mutableListOf<MangaItem>()
                            for (subPage in page until page + 3) {
                                val result = scraper.browseManga(subPage, filters.genre, filters.status, filters.type, filters.sortBy)
                                val items = result.getOrElse { e ->
                                    if (filters.source != null) throw e
                                    emptyList()
                                }
                                if (items.isEmpty()) break
                                aggregate += items
                            }
                            aggregate
                        } else {
                            val result = when {
                                filters.query.isNotEmpty() -> scraper.searchManga(filters.query, page)
                                else -> scraper.browseManga(page, filters.genre, filters.status, filters.type, filters.sortBy)
                            }

                            result.getOrElse { e ->
                                if (filters.source != null) throw e
                                emptyList()
                            }
                        }
                        fetched
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
                nextKey = if (sorted.isEmpty()) null else page + 1
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
    private val readerAnnotationDao: ReaderAnnotationDao,
    private val prefs: AppPreferences
) : LibraryRepository {

    override fun getFavorites(): Flow<List<FavoriteManga>> =
        favoriteDao.getAllFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun addFavorite(manga: FavoriteManga) {
        favoriteDao.insert(manga.toEntity())
        prefs.clearSyncTombstone("favorites", manga.mangaId)
    }
    override suspend fun removeFavorite(mangaId: String) {
        favoriteDao.setFavorite(mangaId, false)
        prefs.markSyncTombstone("favorites", mangaId)
    }
    override suspend fun isFavorite(mangaId: String) = favoriteDao.isFavorite(mangaId)
    override fun isFavoriteFlow(mangaId: String): Flow<Boolean> = favoriteDao.isFavoriteFlow(mangaId)
    override suspend fun getFavoritesByStatus(status: String): List<FavoriteManga> =
        favoriteDao.getByStatus(status).map { it.toDomain() }
    override suspend fun updateReadingStatus(mangaId: String, status: String?) {
        // Ensure entity exists — insert with isFavorite=false if it doesn't
        val existing = favoriteDao.getById(mangaId)
        if (existing == null) {
            // We don't have enough info to create a full entity here,
            // so just create a minimal one. The caller should ensure the entity exists.
            favoriteDao.insert(
                com.exapps.mangaworld.core.data.local.entity.FavoriteEntity(
                    mangaId = mangaId, slug = mangaId, title = mangaId,
                    coverUrl = "", sourceId = "unknown", isFavorite = false, readingStatus = status
                )
            )
        } else {
            favoriteDao.updateReadingStatus(mangaId, status)
        }
        prefs.clearSyncTombstone("favorites", mangaId)
    }

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
        prefs.clearSyncTombstone("readingHistory", mangaId)
        favoriteDao.getById(mangaId)?.let { favorite ->
            favoriteDao.updateProgress(
                mangaId = mangaId,
                read = readCount,
                total = if (totalChapters > 0) totalChapters else favorite.totalChapters
            )
        }
    }

    override suspend fun clearHistory() {
        historyDao.getAllMangaIds().forEach { mangaId -> prefs.markSyncTombstone("readingHistory", mangaId) }
        historyDao.clearAll()
    }
    override suspend fun removeFromHistory(mangaId: String) {
        historyDao.delete(mangaId)
        prefs.markSyncTombstone("readingHistory", mangaId)
    }

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
            prefs.markSyncTombstone("readerAnnotations", annotationDocumentId(mangaId, chapterUrl, pageIndex))
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
            prefs.clearSyncTombstone("readerAnnotations", annotationDocumentId(mangaId, chapterUrl, pageIndex))
        }
    }

    override suspend fun savePageNote(mangaId: String, chapterUrl: String, pageIndex: Int, note: String) {
        val current = readerAnnotationDao.get(mangaId, chapterUrl, pageIndex)
        val normalized = note.trim()
        val keepBookmark = current?.isBookmarked ?: false
        if (normalized.isBlank() && !keepBookmark) {
            readerAnnotationDao.delete(mangaId, chapterUrl, pageIndex)
            prefs.markSyncTombstone("readerAnnotations", annotationDocumentId(mangaId, chapterUrl, pageIndex))
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
            prefs.clearSyncTombstone("readerAnnotations", annotationDocumentId(mangaId, chapterUrl, pageIndex))
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

    private fun annotationDocumentId(mangaId: String, chapterUrl: String, pageIndex: Int): String =
        listOf(mangaId, chapterUrl.hashCode().toString(), pageIndex.toString()).joinToString("_")
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: AppPreferences,
    private val remoteConfigManager: FirebaseRemoteConfigManager
) : SettingsRepository {
    override fun getAppSettings() = combine(prefs.appSettings, remoteConfigManager.disabledSourceIds) { local, remoteDisabled ->
        local.copy(enabledSources = local.enabledSources - remoteDisabled)
    }
    override suspend fun updateTheme(theme: AppTheme) { prefs.setTheme(theme) }
    override suspend fun setOnboardingCompleted(completed: Boolean) { prefs.setOnboardingDone(completed) }
    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) { prefs.setDownloadWifiOnly(enabled) }
    override suspend fun setAutoDownloadNewChapters(enabled: Boolean) { prefs.setAutoDownload(enabled) }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { prefs.setNotifications(enabled) }
    override suspend fun toggleSource(sourceId: String, enabled: Boolean) { prefs.toggleSource(sourceId, enabled) }
    override suspend fun setEnabledSources(sourceIds: Set<String>) { prefs.setEnabledSources(sourceIds) }
    override suspend fun setDynamicColors(enabled: Boolean) { prefs.setDynamicColors(enabled) }
    override suspend fun setBiometricLock(enabled: Boolean) { prefs.setBiometricLock(enabled) }
    override suspend fun setSecureReader(enabled: Boolean) { prefs.setSecureReader(enabled) }
    override suspend fun setNotificationDeliveryMode(mode: NotificationDeliveryMode) { prefs.setNotificationMode(mode) }
    override suspend fun setAutoCleanupReadDownloads(enabled: Boolean) { prefs.setAutoCleanup(enabled) }
    override suspend fun setCleanupAfterHours(hours: Int) { prefs.setCleanupHours(hours) }
    override suspend fun setImageCacheLimitMb(limitMb: Int) { prefs.setImageCacheLimitMb(limitMb) }
    override suspend fun setContentBlacklist(values: Set<String>) { prefs.setContentBlacklist(values) }
    override suspend fun setSpoilerCollapseDefault(enabled: Boolean) { prefs.setSpoilerCollapseDefault(enabled) }
    override suspend fun setMutedUserIds(values: Set<String>) { prefs.setMutedUsers(values) }
    override suspend fun setReadingListStatus(status: String?) { prefs.setReadingListStatus(status) }
    override suspend fun setShowLibraryPublic(enabled: Boolean) { prefs.setShowLibraryPublic(enabled) }
    override fun getReaderSettings() = prefs.readerSettings
    override suspend fun updateReaderMode(mode: ReaderMode) { prefs.setReaderMode(mode) }
    override suspend fun updateBrightness(brightness: Float) { prefs.setBrightness(brightness) }
    override suspend fun updateKeepScreenOn(enabled: Boolean) { prefs.setKeepScreen(enabled) }
    override suspend fun updateAutoWebtoon(enabled: Boolean) { prefs.setAutoWebtoon(enabled) }
    override suspend fun updateIncognitoMode(enabled: Boolean) { prefs.setIncognito(enabled) }
    override suspend fun updateSmartPrefetch(enabled: Boolean) { prefs.setSmartPrefetch(enabled) }
    override suspend fun updateReaderHaptics(enabled: Boolean) { prefs.setReaderHaptics(enabled) }
    override suspend fun updateImageFilter(filter: ReaderImageFilter) { prefs.setImageFilter(filter) }
    override suspend fun updateAutoOpenNextChapter(enabled: Boolean) { prefs.setAutoOpenNext(enabled) }
    override suspend fun updateShowLiveReadersOverlay(enabled: Boolean) { prefs.setShowLiveReaders(enabled) }
    override suspend fun updateShowReactionOverlay(enabled: Boolean) { prefs.setShowReactions(enabled) }
    override suspend fun updateDualPageLandscape(enabled: Boolean) { prefs.setDualPage(enabled) }
    override suspend fun updateWebtoonAutoStitch(enabled: Boolean) { prefs.setWebtoonStitch(enabled) }
    override suspend fun updatePageSpacing(spacing: Int) { prefs.setPageSpacing(spacing) }
    override suspend fun updateVolumeButtonPageTurn(enabled: Boolean) { prefs.setVolumeButton(enabled) }
    override suspend fun updateDoubleTapZoom(enabled: Boolean) { prefs.setDoubleTapZoom(enabled) }
    override suspend fun updateShowPageNumber(enabled: Boolean) { prefs.setShowPageNum(enabled) }
    override suspend fun updateTapActions(left: TapAction, right: TapAction, middle: TapAction) {
        prefs.setTapActions(left, right, middle)
    }
    override fun getCookies(domain: String) = prefs.getCookies(domain)
    override suspend fun saveCookies(domain: String, cookies: String) { prefs.saveCookies(domain, cookies) }
    override suspend fun clearCookies(domain: String) { prefs.clearCookies(domain) }

    override fun isSourceNotificationEnabled(sourceId: String) = prefs.isSourceNotificationEnabled(sourceId)
    override suspend fun setSourceNotification(sourceId: String, enabled: Boolean) { prefs.setSourceNotification(sourceId, enabled) }

    override fun getFavoriteGenres(): Flow<List<String>> = prefs.appSettings.map { it.favoriteGenres }
    override suspend fun setFavoriteGenres(genres: List<String>) { prefs.setFavoriteGenres(genres) }
    override fun getMutedUserIds(): Flow<Set<String>> = prefs.appSettings.map { it.mutedUserIds }
    override suspend fun addMutedUser(uid: String) { val current = prefs.appSettings.first().mutedUserIds; prefs.setMutedUsers(current + uid) }
    override suspend fun removeMutedUser(uid: String) { val current = prefs.appSettings.first().mutedUserIds; prefs.setMutedUsers(current - uid) }
}
