package com.exapps.mangaworld.domain.model

import androidx.annotation.StringRes
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import com.exapps.mangaworld.core.source.plugins.SourceId

// ─── Site enum ───────────────────────────────────────────────────────────────
// REMOVED in v9.0.0 (Phase 3). `MangaSource` was retired in Phase 2
// (@Deprecated(ERROR)) and physically deleted here per the plugin plan —
// use SourceId + SourceRegistry/SourceUiMapper/BuiltinSourceIds instead.
// (The R import left with the enum; per-source display bindings live in
// SourcePlugin.display + SourceDisplayResolver.)

// ─── Manga Type ───────────────────────────────────────────────────────────────
// U-36: display labels are string resources (pattern: TapGridConfig). The
// Arabic literals left in `from()` below are parse rules, not display text.

enum class MangaType(@StringRes val labelRes: Int) {
    MANGA(R.string.manga_type_manga),
    MANHWA(R.string.manga_type_manhwa),
    MANHUA(R.string.manga_type_manhua),
    UNKNOWN(R.string.manga_type_unknown);

    companion object {
        fun from(text: String?): MangaType {
            return when {
                text == null -> UNKNOWN
                text.contains("مانجا", true) -> MANGA
                text.contains("مانهوا", true) -> MANHWA
                text.contains("manhua", true) -> MANHUA
                text.contains("manhwa", true) -> MANHWA
                text.contains("manga", true) -> MANGA
                else -> UNKNOWN
            }
        }
    }
}

// ─── Manga Status ─────────────────────────────────────────────────────────────

enum class MangaStatus(@StringRes val labelRes: Int) {
    ONGOING(R.string.manga_status_ongoing),
    COMPLETED(R.string.manga_status_completed),
    CANCELLED(R.string.manga_status_cancelled),
    HIATUS(R.string.manga_status_hiatus),
    UNKNOWN(R.string.manga_status_unknown);

    companion object {
        fun from(text: String?): MangaStatus {
            return when {
                text == null -> UNKNOWN
                text.contains("ongoing", true) || text.contains("مستمر", true) -> ONGOING
                text.contains("completed", true) || text.contains("مكتمل", true) -> COMPLETED
                text.contains("cancelled", true) || text.contains("ملغي", true) -> CANCELLED
                text.contains("hiatus", true) || text.contains("متوقف", true) -> HIATUS
                else -> UNKNOWN
            }
        }
    }
}

// ─── Manga Models ─────────────────────────────────────────────────────────────

data class MangaItem(
    val id: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: SourceId,
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val type: MangaType = MangaType.UNKNOWN,
    val rating: Float? = null,
    val latestChapter: Int? = null,
    val totalChapters: Int? = null,
    val lastUpdated: Long? = null,
    val isNew: Boolean = false,
    val url: String = "",
    val description: String = ""
)

data class MangaDetail(
    val id: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: SourceId,
    val alternativeTitles: List<String> = emptyList(),
    val authorName: String? = null,
    val artistName: String? = null,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val type: MangaType = MangaType.UNKNOWN,
    val rating: Float? = null,
    val totalChapters: Int = 0,
    val views: String? = null,
    val lastUpdated: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val relatedManga: List<MangaItem> = emptyList(),
    val url: String = ""
)

data class Chapter(
    val id: String,
    val mangaId: String,
    val number: Float,
    val title: String? = null,
    val url: String,
    val coverUrl: String = "",
    val date: Long? = null,
    val dateText: String? = null,
    val views: Int? = null,
    val isRead: Boolean = false,
    val readPage: Int = 0,
    val totalPages: Int = 0,
    val isDownloaded: Boolean = false,
    val isPaid: Boolean = false
) {
    val displayNumber: String
        get() = if (number == number.toInt().toFloat()) number.toInt().toString()
        else number.toString()
}

data class ChapterPage(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

// ─── Home Screen Models ───────────────────────────────────────────────────────

data class HomeData(
    val featured: List<MangaItem> = emptyList(),
    val latestChapters: List<LatestChapterItem> = emptyList(),
    val trending: List<MangaItem> = emptyList()
)

data class LatestChapterItem(
    val mangaId: String,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverUrl: String,
    val chapterNumber: Float,
    val chapterTitle: String? = null,
    val chapterUrl: String,
    val timeAgo: String,
    val publishedAt: Long? = null,
    val source: SourceId,
    val isNew: Boolean = false
)

// ─── Search Filters ───────────────────────────────────────────────────────────

data class SearchFilters(
    val query: String = "",
    val genre: String? = null,
    val status: MangaStatus? = null,
    val type: MangaType? = null,
    val sortBy: SortBy = SortBy.LATEST,
    val source: SourceId? = null,
    val enabledSourceIds: Set<String> = BuiltinSourceIds.ALL,
    val blockedKeywords: Set<String> = emptySet()
)

enum class SortBy(@StringRes val labelRes: Int) {
    LATEST(R.string.sort_latest),
    OLDEST(R.string.sort_oldest),
    POPULARITY(R.string.sort_popularity),
    RATING(R.string.sort_rating)
}

// ─── Library Models ───────────────────────────────────────────────────────────

data class FavoriteManga(
    val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: SourceId,
    val addedAt: Long = System.currentTimeMillis(),
    val readChapters: Int = 0,
    val totalChapters: Int = 0,
    val readingStatus: String? = null,
    val isFavorite: Boolean = true
) {
    val progressPercent: Float
        get() = if (totalChapters > 0) (readChapters.toFloat() / totalChapters).coerceIn(0f, 1f)
        else 0f
}

data class ReadingHistoryItem(
    val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: SourceId,
    /** Raw stored source id — mirrors [source], kept for readers that need the plain string. */
    val sourceId: String = "",
    val lastChapterNumber: Float,
    val lastChapterUrl: String = "",
    val lastReadAt: Long,
    val readChapters: Int = 0,
    val totalChapters: Int = 0
) {
    val progressPercent: Float
        get() = if (totalChapters > 0) (readChapters.toFloat() / totalChapters).coerceIn(0f, 1f)
        else 0f
}

// ─── Reader Settings ──────────────────────────────────────────────────────────

enum class ReaderMode(@StringRes val labelRes: Int) {
    VERTICAL_SCROLL(R.string.reader_mode_vertical_scroll),
    HORIZONTAL_RTL(R.string.reader_mode_horizontal_rtl),
    HORIZONTAL_LTR(R.string.reader_mode_horizontal_ltr),
    WEBTOON(R.string.reader_mode_webtoon)
}

enum class ReaderImageFilter(@StringRes val labelRes: Int) {
    NONE(R.string.reader_filter_none),
    GRAYSCALE(R.string.reader_filter_grayscale),
    SEPIA(R.string.reader_filter_sepia),
    HIGH_CONTRAST(R.string.reader_filter_high_contrast),
    SMART_CROP(R.string.reader_filter_smart_crop),
    WARM_TINT(R.string.reader_filter_warm_tint),
    COOL_TINT(R.string.reader_filter_cool_tint),
    OLED_BLACK(R.string.reader_filter_oled_black)
}

// Note: this is the persisted reader-setting enum (AppPreferences + backup
// store it by `name`); the tap-grid UI uses TapGridConfig.TapAction, which
// already carries labelRes. labelRes exists here for parity/localization.
enum class TapAction(@StringRes val labelRes: Int) {
    PREV_PAGE(R.string.tap_prev_page),
    NEXT_PAGE(R.string.tap_next_page),
    TOGGLE_CONTROLS(R.string.tap_toggle_controls),
    BOOKMARK(R.string.tap_bookmark),
    NONE(R.string.tap_none)
}

data class ReaderPageAnnotation(
    val pageIndex: Int,
    val note: String? = null,
    val isBookmarked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ReaderSettings(
    val mode: ReaderMode = ReaderMode.VERTICAL_SCROLL,
    val brightness: Float = 1.0f,
    val pageSpacing: Int = 0,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val autoWebtoonDetection: Boolean = true,
    val incognitoMode: Boolean = false,
    val smartPrefetchEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val imageFilter: ReaderImageFilter = ReaderImageFilter.NONE,
    val autoOpenNextChapter: Boolean = false,
    val showLiveReadersOverlay: Boolean = true,
    val showReactionOverlay: Boolean = true,
    val dualPageLandscape: Boolean = false,
    val webtoonAutoStitch: Boolean = true,
    val volumeButtonPageTurn: Boolean = false,
    val doubleTapZoom: Boolean = true,
    val tapLeftAction: TapAction = TapAction.PREV_PAGE,
    val tapRightAction: TapAction = TapAction.NEXT_PAGE,
    val tapMiddleAction: TapAction = TapAction.TOGGLE_CONTROLS
)

enum class NotificationDeliveryMode(@StringRes val labelRes: Int) {
    INSTANT(R.string.notification_mode_instant),
    DAILY_DIGEST(R.string.notification_mode_daily_digest),
    SILENT(R.string.notification_mode_silent)
}

// ─── App Settings ─────────────────────────────────────────────────────────────

enum class AppTheme(@StringRes val labelRes: Int) {
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light),
    SYSTEM(R.string.theme_system)
}

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    val downloadOnWifiOnly: Boolean = true,
    val autoDownloadNewChapters: Boolean = false,
    val enableNotifications: Boolean = true,
    val notifyComments: Boolean = true,
    val notifyLikes: Boolean = true,
    val notifyFollowers: Boolean = true,
    val enabledSources: Set<String> = BuiltinSourceIds.ALL,
    val onboardingCompleted: Boolean = false,
    val useDynamicColors: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val secureReaderEnabled: Boolean = false,
    val notificationDeliveryMode: NotificationDeliveryMode = NotificationDeliveryMode.INSTANT,
    val autoCleanupReadDownloads: Boolean = false,
    val cleanupAfterHours: Int = 24,
    val imageCacheLimitMb: Int = 250,
    val contentBlacklist: Set<String> = emptySet(),
    val spoilerCollapseDefault: Boolean = true,
    val mutedUserIds: Set<String> = emptySet(),
    val readingListStatus: String? = null,
    val favoriteGenres: List<String> = emptyList(),
    val showLibraryPublic: Boolean = true,
    val appLanguage: String = "ar",
    /** Last source opened on Home — restored on launch instead of AZORA (item 10). */
    val lastSourceId: String = "azora"
) { val isRtl get() = appLanguage == "ar" }

enum class CommunityNotificationType {
    REPLY,
    MENTION,
    REVIEW_REACTION,
    COMMENT_THREAD,
    CHAT_MENTION,
    SYSTEM_ALERT,
    FOLLOW
}

data class CommunityProfile(
    val uid: String,
    val username: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val badgeLabel: String = "Beginner",
    val role: String = "viewer",
    val isPublic: Boolean = true,
    val showListsPublic: Boolean = true,
    val showActivityPublic: Boolean = true,
    val showLibraryPublic: Boolean = true,
    val bio: String = "",
    val location: String = "",
    /** Birthday as epoch millis; null when unset (optional field). */
    val birthday: Long? = null,
    /**
     * Account creation time (A-14). 0 for profiles written before v8.4.5 —
     * callers fall back to updatedAt and upserts backfill it.
     */
    val createdAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

data class CustomUserList(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String = "",
    val rating: Float = 0f,
    val genres: List<String> = emptyList(),
    val isPublic: Boolean = false,
    val itemCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class CustomUserListItem(
    val mangaId: String,
    val sourceId: String,
    val slug: String,
    val title: String,
    val coverUrl: String = "",
    val rating: Float = 0f,
    val genres: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis()
)

data class CommunityComment(
    val id: String,
    val mangaId: String,
    val chapterUrl: String? = null,
    val slug: String = "",
    val sourceId: String = "",
    val parentId: String? = null,
    /** The root comment ID for a flat reply thread. Legacy nested replies omit this value. */
    val threadRootId: String? = null,
    /** Non-null when this comment is a reply to a manga review rather than a comment. */
    val reviewId: String? = null,
    /** Explicit addressee for a flat reply; falls back to the thread author when absent. */
    val replyToUid: String? = null,
    val replyToUsername: String? = null,
    val authorUid: String,
    val authorName: String,
    val authorUsername: String = "",
    val authorAvatarUrl: String? = null,
    val authorBadge: String = "Beginner",
    val text: String,
    val mentions: List<String> = emptyList(),
    val spoiler: Boolean = false,
    val isDeleted: Boolean = false,
    val editedAt: Long? = null,
    val reportedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val replyCount: Int = 0,
    val likes: Int = 0,
    val dislikes: Int = 0
)

data class MangaReview(
    val id: String,
    val mangaId: String,
    val authorUid: String,
    val authorName: String,
    val authorUsername: String = "",
    val authorAvatarUrl: String? = null,
    val authorBadge: String = "Beginner",
    val rating: Int,
    val title: String = "",
    val body: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val replyCount: Int = 0,
    val likes: Int = 0,
    val dislikes: Int = 0,
    val reportedCount: Int = 0,
    val isDeleted: Boolean = false
)

/** Metadata used when a reply is sent from the dedicated, flat replies screen. */
data class CommunityReplyTarget(
    val parentId: String? = null,
    val reviewId: String? = null,
    val replyToUid: String? = null,
    val replyToUsername: String? = null
)

data class ReaderReaction(
    val id: String,
    val mangaId: String,
    val chapterUrl: String,
    val pageIndex: Int,
    val emoji: String,
    val authorUid: String,
    val authorName: String,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis()
)

data class CommunityNotification(
    val id: String,
    val type: CommunityNotificationType,
    val title: String,
    val body: String,
    val mangaId: String,
    val slug: String,
    val sourceId: String,
    val chapterUrl: String? = null,
    val commentId: String? = null,
    /** FOLLOW notifications carry the follower's uid (manga fields are blank). */
    val targetUid: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val read: Boolean = false
)

/**
 * Authoritative result of a like/dislike vote (server transaction).
 * All-defaults so relaxed test doubles stay safe. `myVote`: 1 = liked,
 * -1 = disliked, null = retracted/no vote.
 */
data class VoteOutcome(
    val likes: Int = 0,
    val dislikes: Int = 0,
    val myVote: Int? = null,
    val action: String = "added"
)

data class ModerationReport(
    val id: String,
    val commentId: String,
    val targetId: String = commentId,
    val mangaId: String,
    val chapterUrl: String? = null,
    val reportedUid: String,
    val reporterUid: String,
    val reason: String,
    val createdAt: Long,
    val status: String = "open",
    val targetType: String = "comment"
)

data class CommunityChatMessage(
    val id: String,
    val roomId: String,
    val authorUid: String,
    val authorName: String,
    val authorBadge: String = "Beginner",
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class UserFollow(
    val uid: String,
    val username: String = "",
    val avatarUrl: String? = null,
    val followedAt: Long = System.currentTimeMillis()
)

// ─── Security centre ─────────────────────────────────────────────────────────

/** One recorded sign-in (users/{uid}/loginLogs). */
data class LoginLogEntry(
    val id: String,
    val createdAt: Long,
    val deviceLabel: String = "",
    val appVersion: String = "",
    val provider: String = ""
)

/** One registered push device (users/{uid}/devices). */
data class DeviceEntry(
    val id: String,
    val tokenSuffix: String = "",
    val platform: String = "",
    val sdkInt: Int = 0,
    val updatedAt: Long = 0L,
    val isCurrent: Boolean = false
)

/** One app session (users/{uid}/sessions). */
data class SessionEntry(
    val id: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val deviceLabel: String = "",
    val appVersion: String = "",
    val revoked: Boolean = false,
    val isCurrent: Boolean = false
)

/**
 * `label` is the persisted storage key (Room `readingStatus`, cloud sync) —
 * NEVER display text. Display uses [labelRes] (the former `arabicLabel`
 * string, unused in prod: LibraryBottomSheet already showed localized
 * R.string.library_* text).
 */
enum class ReadingListStatus(val label: String, @StringRes val labelRes: Int) {
    READING("reading", R.string.reading_status_reading),
    PLAN_TO_READ("plan_to_read", R.string.reading_status_plan_to_read),
    COMPLETED("completed", R.string.reading_status_completed),
    ON_HOLD("on_hold", R.string.reading_status_on_hold),
    DROPPED("dropped", R.string.reading_status_dropped)
}

data class CloudRestorePreview(
    val localFavorites: Int,
    val remoteFavorites: Int,
    val localHistory: Int,
    val remoteHistory: Int,
    val localAnnotations: Int,
    val remoteAnnotations: Int,
    val localReadMarks: Int = 0,
    val remoteReadMarks: Int = 0,
    val localLatestHistoryAt: Long,
    val remoteLatestHistoryAt: Long,
    val localLatestAnnotationAt: Long,
    val remoteLatestAnnotationAt: Long,
    val remoteTheme: AppTheme? = null,
    val localTheme: AppTheme,
    /** True when any cloud collection hit the 10k fetch cap (FS-11). */
    val truncated: Boolean = false,
    val suggestedStrategy: CloudRestoreStrategy
)

enum class CloudRestoreStrategy {
    MERGE,
    REMOTE_OVERWRITE,
    KEEP_LOCAL
}
