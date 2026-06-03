package com.exapps.mangaworld.domain.repository

import androidx.paging.PagingData
import com.exapps.mangaworld.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    // Home
    suspend fun getHomeData(source: MangaSource): Result<HomeData>

    // Browse / Search
    fun searchManga(filters: SearchFilters): Flow<PagingData<MangaItem>>
    suspend fun searchMangaDirect(query: String, source: MangaSource, page: Int = 1): Result<List<MangaItem>>
    suspend fun getMangaByGenre(genre: String, source: MangaSource, page: Int): Result<List<MangaItem>>
    fun observeSourceBrowseMetadata(): Flow<List<SourceBrowseMetadata>>
    suspend fun refreshSourceBrowseMetadata(source: MangaSource): SourceBrowseMetadata

    // Detail
    suspend fun getMangaDetail(slug: String, source: MangaSource): Result<MangaDetail>

    // Chapters
    suspend fun getChapterPages(mangaSlug: String, chapterUrl: String, source: MangaSource): Result<List<ChapterPage>>

    // Popular / Featured
    suspend fun getPopularManga(source: MangaSource): Result<List<MangaItem>>
    suspend fun getSuggestedManga(candidates: List<MangaItem>, limit: Int = 12): List<MangaItem>

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
        source: MangaSource, chapterNumber: Float, chapterUrl: String, totalChapters: Int
    )
    suspend fun clearHistory()
    suspend fun removeFromHistory(mangaId: String)

    // Read Chapters
    suspend fun markChapterRead(mangaId: String, chapterNumber: Float)
    suspend fun markChapterUnread(mangaId: String, chapterNumber: Float)
    suspend fun isChapterRead(mangaId: String, chapterNumber: Float): Boolean
    fun getReadChapters(mangaId: String): Flow<Set<Float>>
    suspend fun saveReadingProgress(mangaId: String, chapterNumber: Float, page: Int, totalPages: Int)
    suspend fun getReadingProgress(mangaId: String, chapterNumber: Float): Pair<Int, Int>
    suspend fun getReadingProgressMap(mangaId: String): Map<Float, Pair<Int, Int>>
    fun observeReaderAnnotations(mangaId: String, chapterUrl: String): Flow<List<ReaderPageAnnotation>>
    suspend fun togglePageBookmark(mangaId: String, chapterUrl: String, pageIndex: Int)
    suspend fun savePageNote(mangaId: String, chapterUrl: String, pageIndex: Int, note: String)
    suspend fun getPageAnnotation(mangaId: String, chapterUrl: String, pageIndex: Int): ReaderPageAnnotation?
}

interface SettingsRepository {
    fun getAppSettings(): Flow<AppSettings>
    suspend fun updateTheme(theme: AppTheme)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setDownloadOnWifiOnly(enabled: Boolean)
    suspend fun setAutoDownloadNewChapters(enabled: Boolean)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun toggleSource(sourceId: String, enabled: Boolean)
    suspend fun setEnabledSources(sourceIds: Set<String>)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setBiometricLock(enabled: Boolean)
    suspend fun setSecureReader(enabled: Boolean)
    suspend fun setNotificationDeliveryMode(mode: NotificationDeliveryMode)
    suspend fun setAutoCleanupReadDownloads(enabled: Boolean)
    suspend fun setCleanupAfterHours(hours: Int)
    suspend fun setImageCacheLimitMb(limitMb: Int)
    suspend fun setContentBlacklist(values: Set<String>)
    suspend fun setSpoilerCollapseDefault(enabled: Boolean)
    suspend fun setMutedUserIds(values: Set<String>)
    suspend fun setMlKitEnabled(enabled: Boolean)
    suspend fun setDownloadBandwidthCapKb(kb: Int)

    fun getReaderSettings(): Flow<ReaderSettings>
    suspend fun updateReaderMode(mode: ReaderMode)
    suspend fun updateBrightness(brightness: Float)
    suspend fun updateKeepScreenOn(enabled: Boolean)
    suspend fun updateAutoWebtoon(enabled: Boolean)
    suspend fun updateIncognitoMode(enabled: Boolean)
    suspend fun updateSmartPrefetch(enabled: Boolean)
    suspend fun updateReaderHaptics(enabled: Boolean)
    suspend fun updateImageFilter(filter: ReaderImageFilter)
    suspend fun updateAutoOpenNextChapter(enabled: Boolean)
    suspend fun updateShowLiveReadersOverlay(enabled: Boolean)
    suspend fun updateShowReactionOverlay(enabled: Boolean)
    suspend fun updateDualPageLandscape(enabled: Boolean)
    suspend fun updateWebtoonAutoStitch(enabled: Boolean)
    suspend fun updatePageTurnVolumeKeys(enabled: Boolean)
    suspend fun updateTapToTurnPages(enabled: Boolean)
    suspend fun updateReadingDirectionLocked(enabled: Boolean)

    fun getCookies(domain: String): Flow<String?>
    suspend fun saveCookies(domain: String, cookies: String)
    suspend fun clearCookies(domain: String)
}

interface CommunityRepository {
    fun observeMangaComments(mangaId: String): Flow<List<CommunityComment>>
    fun observeChapterComments(mangaId: String, chapterUrl: String): Flow<List<CommunityComment>>
    fun observeReviews(mangaId: String): Flow<List<MangaReview>>
    fun observeReaderPresenceCount(mangaId: String, chapterUrl: String): Flow<Int>
    fun observePageReactions(mangaId: String, chapterUrl: String, pageIndex: Int): Flow<List<ReaderReaction>>
    fun observeNotifications(limit: Int = 50): Flow<List<CommunityNotification>>
    fun observeChatMessages(roomId: String = "global"): Flow<List<CommunityChatMessage>>
    fun observeUserLists(): Flow<List<CustomUserList>>
    fun observeListItems(listId: String): Flow<List<CustomUserListItem>>
    fun observePublicProfile(userId: String): Flow<CommunityProfile?>
    fun observePublicLists(userId: String): Flow<List<CustomUserList>>
    fun observePublicActivity(userId: String): Flow<List<CommunityComment>>
    fun observeModerationReports(): Flow<List<ModerationReport>>
    suspend fun getCurrentProfile(): CommunityProfile?
    suspend fun upsertProfile(username: String, bio: String, isPublic: Boolean)
    suspend fun updateProfilePrivacy(showListsPublic: Boolean, showActivityPublic: Boolean)
    suspend fun createOrUpdateList(listId: String?, name: String, description: String, isPublic: Boolean): String
    suspend fun deleteList(listId: String)
    suspend fun addMangaToList(listId: String, item: CustomUserListItem)
    suspend fun removeMangaFromList(listId: String, mangaId: String)
    suspend fun postMangaComment(mangaId: String, slug: String, sourceId: String, text: String, spoiler: Boolean = false, parentId: String? = null)
    suspend fun postChapterComment(mangaId: String, slug: String, sourceId: String, chapterUrl: String, text: String, spoiler: Boolean = false, parentId: String? = null)
    suspend fun upsertReview(mangaId: String, slug: String, sourceId: String, rating: Int, title: String, body: String)
    suspend fun sendPageReaction(mangaId: String, chapterUrl: String, pageIndex: Int, emoji: String, normalizedX: Float, normalizedY: Float)
    suspend fun sendChatMessage(roomId: String = "global", text: String)
    suspend fun reportComment(comment: CommunityComment, reason: String)
    suspend fun resolveModerationReport(reportId: String, status: String)
    suspend fun setReaderPresence(mangaId: String, chapterUrl: String, active: Boolean)
    suspend fun markNotificationRead(notificationId: String)
}
