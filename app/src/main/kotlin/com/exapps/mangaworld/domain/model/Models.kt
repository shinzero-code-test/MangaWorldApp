package com.exapps.mangaworld.domain.model

// ─── Site Enum ───────────────────────────────────────────────────────────────

enum class MangaSource(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val requiresVerification: Boolean = false
) {
    OLYMPUS("olympus", "Olympus Staff", "https://olympustaff.com", true),
    AZORA("azora", "Azora Moon", "https://azoramoon.com", false),
    STARZ("starz", "Manga Starz", "https://manga-starz.net", true),
    MANGASID("mangasid", "Manga Sid", "https://mangasid.com", false),
    MESHMANGA("meshmanga", "Meshmanga", "https://meshmanga.com", false);

    companion object {
        fun fromId(id: String) = values().find { it.id == id } ?: AZORA
    }
}

// ─── Manga Type ───────────────────────────────────────────────────────────────

enum class MangaType(val label: String) {
    MANGA("مانجا"),
    MANHWA("مانهوا"),
    MANHUA("مانهوا"),
    UNKNOWN("غير محدد");

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

enum class MangaStatus(val label: String) {
    ONGOING("مستمر"),
    COMPLETED("مكتمل"),
    CANCELLED("ملغي"),
    HIATUS("متوقف"),
    UNKNOWN("غير محدد");

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
    val source: MangaSource,
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val type: MangaType = MangaType.UNKNOWN,
    val rating: Float? = null,
    val latestChapter: Int? = null,
    val totalChapters: Int? = null,
    val lastUpdated: Long? = null,
    val isNew: Boolean = false,
    val url: String = ""
)

data class MangaDetail(
    val id: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: MangaSource,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val type: MangaType = MangaType.UNKNOWN,
    val rating: Float? = null,
    val totalChapters: Int = 0,
    val views: String? = null,
    val lastUpdated: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val url: String = ""
)

data class Chapter(
    val id: String,
    val mangaId: String,
    val number: Float,
    val title: String? = null,
    val url: String,
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
    val source: MangaSource,
    val isNew: Boolean = false
)

// ─── Search Filters ───────────────────────────────────────────────────────────

data class SearchFilters(
    val query: String = "",
    val genre: String? = null,
    val status: MangaStatus? = null,
    val type: MangaType? = null,
    val sortBy: SortBy = SortBy.LATEST,
    val source: MangaSource? = null
)

enum class SortBy(val label: String) {
    LATEST("الأحدث"),
    OLDEST("الأقدم"),
    POPULARITY("الأكثر شعبية"),
    RATING("الأعلى تقييماً")
}

// ─── Library Models ───────────────────────────────────────────────────────────

data class FavoriteManga(
    val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val source: MangaSource,
    val addedAt: Long = System.currentTimeMillis(),
    val readChapters: Int = 0,
    val totalChapters: Int = 0
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
    val source: MangaSource,
    val lastChapterNumber: Float,
    val lastReadAt: Long,
    val readChapters: Int = 0,
    val totalChapters: Int = 0
) {
    val progressPercent: Float
        get() = if (totalChapters > 0) (readChapters.toFloat() / totalChapters).coerceIn(0f, 1f)
        else 0f
}

// ─── Reader Settings ──────────────────────────────────────────────────────────

enum class ReaderMode(val label: String) {
    VERTICAL_SCROLL("تمرير عمودي"),
    HORIZONTAL_RTL("أفقي (يمين لشمال)"),
    HORIZONTAL_LTR("أفقي (شمال ليمين)"),
    WEBTOON("ويب تون")
}

data class ReaderSettings(
    val mode: ReaderMode = ReaderMode.VERTICAL_SCROLL,
    val brightness: Float = 1.0f,
    val pageSpacing: Int = 0,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val autoWebtoonDetection: Boolean = true
)

// ─── App Settings ─────────────────────────────────────────────────────────────

enum class AppTheme(val label: String) { DARK("داكن"), LIGHT("فاتح"), SYSTEM("تلقائي") }

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    val downloadOnWifiOnly: Boolean = true,
    val autoDownloadNewChapters: Boolean = false,
    val enableNotifications: Boolean = true,
    val enabledSources: Set<String> = MangaSource.values().map { it.id }.toSet(),
    val onboardingCompleted: Boolean = false
)
