package com.exapps.mangaworld.domain.repository

import androidx.paging.PagingData
import com.exapps.mangaworld.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    // Home
    suspend fun getHomeData(source: MangaSource): Result<HomeData>

    // Browse / Search
    fun searchManga(filters: SearchFilters): Flow<PagingData<MangaItem>>
    suspend fun getMangaByGenre(genre: String, source: MangaSource, page: Int): Result<List<MangaItem>>

    // Detail
    suspend fun getMangaDetail(slug: String, source: MangaSource): Result<MangaDetail>

    // Chapters
    suspend fun getChapterPages(mangaSlug: String, chapterUrl: String, source: MangaSource): Result<List<ChapterPage>>

    // Popular / Featured
    suspend fun getPopularManga(source: MangaSource): Result<List<MangaItem>>

    // Genres
    suspend fun getGenres(source: MangaSource? = null, enabledSourceIds: Set<String>? = null): List<String>
}

interface LibraryRepository {
    // Favorites
    fun getFavorites(): Flow<List<FavoriteManga>>
    suspend fun addFavorite(manga: FavoriteManga)
    suspend fun removeFavorite(mangaId: String)
    suspend fun isFavorite(mangaId: String): Boolean
    fun isFavoriteFlow(mangaId: String): Flow<Boolean>

    // Reading History
    fun getReadingHistory(): Flow<List<ReadingHistoryItem>>
    suspend fun updateReadingHistory(
        mangaId: String, slug: String, title: String, coverUrl: String,
        source: MangaSource, chapterNumber: Float, totalChapters: Int
    )
    suspend fun clearHistory()
    suspend fun removeFromHistory(mangaId: String)

    // Read Chapters
    suspend fun markChapterRead(mangaId: String, chapterNumber: Float)
    suspend fun markChapterUnread(mangaId: String, chapterNumber: Float)
    fun getReadChapters(mangaId: String): Flow<Set<Float>>
    suspend fun saveReadingProgress(mangaId: String, chapterNumber: Float, page: Int, totalPages: Int)
    suspend fun getReadingProgress(mangaId: String, chapterNumber: Float): Pair<Int, Int>
    suspend fun getReadingProgressMap(mangaId: String): Map<Float, Pair<Int, Int>>
}

interface SettingsRepository {
    fun getAppSettings(): Flow<AppSettings>
    suspend fun updateTheme(theme: AppTheme)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setDownloadOnWifiOnly(enabled: Boolean)
    suspend fun setAutoDownloadNewChapters(enabled: Boolean)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun toggleSource(sourceId: String, enabled: Boolean)

    fun getReaderSettings(): Flow<ReaderSettings>
    suspend fun updateReaderMode(mode: ReaderMode)
    suspend fun updateBrightness(brightness: Float)
    suspend fun updateKeepScreenOn(enabled: Boolean)
    suspend fun updateAutoWebtoon(enabled: Boolean)

    fun getCookies(domain: String): Flow<String?>
    suspend fun saveCookies(domain: String, cookies: String)
    suspend fun clearCookies(domain: String)
}
