package com.exapps.mangaworld.core.data

data class WidgetMangaEntry(
    val mangaId: String,
    val sourceId: String,
    val slug: String,
    val title: String,
    val coverUrl: String = "",
    val subtitle: String? = null
)

data class WidgetLatestUpdateEntry(
    val mangaId: String,
    val sourceId: String,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverUrl: String = "",
    val chapterLabel: String,
    val chapterUrl: String,
    val publishedAt: Long? = null,
    val timeAgo: String? = null
)

data class ContinueReadingWidgetData(
    val mangaId: String,
    val sourceId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val chapterLabel: String,
    val chapterUrl: String
)

data class LibraryWidgetEntry(
    val mangaId: String,
    val sourceId: String,
    val slug: String,
    val title: String,
    val newChapterCount: Int
)

data class ReadingStatsWidgetData(
    val totalChaptersRead: Int,
    val readingStreakDays: Int,
    val totalReadingMinutes: Long
)

data class RemoteWidgetsSnapshot(
    val generatedAt: Long,
    val recommendation: WidgetMangaEntry? = null,
    val trending: WidgetMangaEntry? = null,
    val latestUpdates: List<WidgetLatestUpdateEntry> = emptyList()
)
