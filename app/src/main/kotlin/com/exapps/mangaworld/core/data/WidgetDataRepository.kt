package com.exapps.mangaworld.core.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.disk.DiskCache
import coil.ImageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.domain.model.Chapter
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class WidgetDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readChapterDao: ReadChapterDao,
    private val cacheDao: MangaCacheDao,
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository,
    private val snapshotStore: WidgetSnapshotStore,
    private val readingStatsStore: ReadingStatsStore,
    okHttpClient: OkHttpClient
) {

    private val imageLoader by lazy {
        val limitMb = runCatching { kotlinx.coroutines.runBlocking { settingsRepository.getAppSettings().first().imageCacheLimitMb } }.getOrDefault(250)
        ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "coil_image_cache"))
                    .maxSizeBytes(limitMb.coerceAtLeast(64).toLong() * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    suspend fun getContinueReading(): ContinueReadingWidgetData? {
        val latest = historyDao.getLatest() ?: return null
        return latest.toContinueReadingData()
    }

    suspend fun getLibraryEntries(limit: Int = 3): List<LibraryWidgetEntry> {
        val favoriteMap = favoriteDao.getFavoritesList().associateBy { it.mangaId }
        if (favoriteMap.isEmpty()) return emptyList()

        val recentLibrary = historyDao.getRecent(limit * 3)
            .filter { it.mangaId in favoriteMap.keys }
            .distinctBy { it.mangaId }
            .take(limit)

        val cacheMap = cacheDao.getByIds(recentLibrary.map { it.mangaId }).associateBy { it.mangaId }

        return recentLibrary.mapNotNull { item ->
            val favorite = favoriteMap[item.mangaId] ?: return@mapNotNull null
            val cache = cacheMap[item.mangaId]
            val totalChapters = cache?.totalChapters ?: favorite.totalChapters
            LibraryWidgetEntry(
                mangaId = item.mangaId,
                sourceId = item.sourceId,
                slug = item.slug,
                title = item.title,
                newChapterCount = (totalChapters - favorite.readChapters).coerceAtLeast(0)
            )
        }
    }

    suspend fun getReadingStats(): ReadingStatsWidgetData {
        val totalChaptersRead = readChapterDao.getTotalReadCount()
        val readDays = readChapterDao.getReadTimestamps()
            .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            .distinct()
            .sortedDescending()

        val streak = computeReadingStreak(readDays)
        val totalReadingMinutes = (readingStatsStore.totalReadingTimeMs.first() / 60_000L).coerceAtLeast(0L)

        return ReadingStatsWidgetData(
            totalChaptersRead = totalChaptersRead,
            readingStreakDays = streak,
            totalReadingMinutes = totalReadingMinutes
        )
    }

    suspend fun getRecentReadingTargets(limit: Int = 4): List<ContinueReadingWidgetData> =
        buildList {
            historyDao.getRecent(limit * 2).forEach { item ->
                item.toContinueReadingData()?.let(::add)
            }
        }.distinctBy { it.mangaId }.take(limit)

    suspend fun getRandomMangaTarget(): WidgetMangaEntry? {
        val cache = cacheDao.getRandom()
        if (cache != null) {
            val source = MangaSource.fromId(cache.sourceId)
            return WidgetMangaEntry(
                mangaId = cache.mangaId,
                sourceId = source.id,
                slug = cache.slug,
                title = cache.title,
                coverUrl = cache.coverUrl,
                subtitle = source.displayName
            )
        }

        val favorite = favoriteDao.getFavoritesList().firstOrNull()
        if (favorite != null) {
            return WidgetMangaEntry(
                mangaId = favorite.mangaId,
                sourceId = favorite.sourceId,
                slug = favorite.slug,
                title = favorite.title,
                coverUrl = favorite.coverUrl,
                subtitle = MangaSource.fromId(favorite.sourceId).displayName
            )
        }

        val history = historyDao.getLatest() ?: return null
        return WidgetMangaEntry(
            mangaId = history.mangaId,
            sourceId = history.sourceId,
            slug = history.slug,
            title = history.title,
            coverUrl = history.coverUrl,
            subtitle = MangaSource.fromId(history.sourceId).displayName
        )
    }

    suspend fun getRemoteSnapshot(forceRefresh: Boolean = false): RemoteWidgetsSnapshot {
        val cached = snapshotStore.readRemoteSnapshot()
        if (!forceRefresh && cached != null && cached.latestUpdates.isNotEmpty()) return cached
        return refreshRemoteSnapshot()
    }

    suspend fun refreshRemoteSnapshot(): RemoteWidgetsSnapshot = coroutineScope {
        val settings = settingsRepository.getAppSettings().first()
        val enabledSourceIds = settings.enabledSources
        val sources = MangaSource.entries.filter { it.id in enabledSourceIds }

        val homeData = sources.map { source ->
            async { source to mangaRepository.getHomeData(source).getOrNull() }
        }.awaitAll()

        val validHomes = homeData.mapNotNull { (source, data) -> data?.let { source to it } }

        val featured = validHomes.flatMap { (source, data) -> data.featured.map { source to it } }
            .distinctBy { it.second.id }
            .filterNot { it.second.isBlockedBy(settings.contentBlacklist) }
        val trending = validHomes.flatMap { (source, data) -> data.trending.map { source to it } }
            .distinctBy { it.second.id }
            .filterNot { it.second.isBlockedBy(settings.contentBlacklist) }
        val latest = validHomes.flatMap { (_, data) -> data.latestChapters }
            .distinctBy { it.chapterUrl }
            .filterNot { it.isBlockedBy(settings.contentBlacklist) }

        val recentMangaIds = historyDao.getRecent(10).map { it.mangaId }.toSet()

        val recommendationPair = featured.firstOrNull { (_, manga) -> manga.id !in recentMangaIds }
            ?: trending.firstOrNull { (_, manga) -> manga.id !in recentMangaIds }
            ?: featured.firstOrNull()
            ?: trending.firstOrNull()

        val snapshot = RemoteWidgetsSnapshot(
            generatedAt = System.currentTimeMillis(),
            recommendation = recommendationPair?.toWidgetMangaEntry(),
            trending = trending.firstOrNull()?.toWidgetMangaEntry(),
            latestUpdates = latest.take(6).map { it.toWidgetLatestUpdateEntry() }
        )

        snapshotStore.saveRemoteSnapshot(snapshot)
        snapshot
    }

    suspend fun loadCoverBitmap(url: String?, width: Int, height: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(width, height)
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                result.drawable?.toBitmap(width = width, height = height)
            }.getOrNull()
        }
    }

    private suspend fun com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity.toContinueReadingData(): ContinueReadingWidgetData? {
        val source = MangaSource.fromId(sourceId)
        val chapterUrl = lastChapterUrl.ifBlank {
            resolveChapter(this, source)?.url.orEmpty()
        }
        if (chapterUrl.isBlank()) return null
        return ContinueReadingWidgetData(
            mangaId = mangaId,
            sourceId = source.id,
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            chapterLabel = "الفصل ${formatChapterNumber(lastChapterNumber)}",
            chapterUrl = chapterUrl
        )
    }

    private suspend fun resolveChapter(
        item: com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity,
        source: MangaSource
    ): Chapter? {
        val detail = cacheDao.get(item.mangaId)?.toDetail(source)
            ?: mangaRepository.getMangaDetail(item.slug, source).getOrNull()
        return detail?.chapters
            ?.minByOrNull { kotlin.math.abs(it.number - item.lastChapterNumber) }
    }

    private fun computeReadingStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0
        var streak = 0
        var expected = days.first()
        for (day in days) {
            if (day == expected) {
                streak++
                expected = expected.minusDays(1)
            } else if (day.isBefore(expected)) {
                break
            }
        }
        return streak
    }

    private fun Pair<MangaSource, com.exapps.mangaworld.domain.model.MangaItem>.toWidgetMangaEntry(): WidgetMangaEntry =
        WidgetMangaEntry(
            mangaId = second.id,
            sourceId = first.id,
            slug = second.slug,
            title = second.title,
            coverUrl = second.coverUrl,
            subtitle = first.displayName
        )

    private fun LatestChapterItem.toWidgetLatestUpdateEntry(): WidgetLatestUpdateEntry = WidgetLatestUpdateEntry(
        mangaId = mangaId,
        sourceId = source.id,
        mangaSlug = mangaSlug,
        mangaTitle = mangaTitle,
        coverUrl = coverUrl,
        chapterLabel = "الفصل ${formatChapterNumber(chapterNumber)}",
        chapterUrl = chapterUrl,
        publishedAt = publishedAt,
        timeAgo = timeAgo.ifBlank { null }
    )

    private fun formatChapterNumber(number: Float): String =
        if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()
}
