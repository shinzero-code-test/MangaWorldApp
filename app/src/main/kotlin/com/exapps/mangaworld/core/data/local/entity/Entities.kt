package com.exapps.mangaworld.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.exapps.mangaworld.domain.model.*

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val readChapters: Int = 0,
    val totalChapters: Int = 0
) {
    fun toDomain() = FavoriteManga(
        mangaId = mangaId, slug = slug, title = title, coverUrl = coverUrl,
        source = MangaSource.fromId(sourceId), addedAt = addedAt,
        readChapters = readChapters, totalChapters = totalChapters
    )
}

fun FavoriteManga.toEntity() = FavoriteEntity(
    mangaId = mangaId, slug = slug, title = title, coverUrl = coverUrl,
    sourceId = source.id, addedAt = addedAt, readChapters = readChapters,
    totalChapters = totalChapters
)

@Entity(tableName = "reading_history")
data class ReadingHistoryEntity(
    @PrimaryKey val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val lastChapterNumber: Float,
    val lastReadAt: Long,
    val readChapters: Int = 0,
    val totalChapters: Int = 0
) {
    fun toDomain() = ReadingHistoryItem(
        mangaId = mangaId, slug = slug, title = title, coverUrl = coverUrl,
        source = MangaSource.fromId(sourceId), lastChapterNumber = lastChapterNumber,
        lastReadAt = lastReadAt, readChapters = readChapters, totalChapters = totalChapters
    )
}

@Entity(tableName = "read_chapters", primaryKeys = ["mangaId", "chapterNumber"])
data class ReadChapterEntity(
    val mangaId: String,
    val chapterNumber: Float,
    val readAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_progress", primaryKeys = ["mangaId", "chapterNumber"])
data class ReadingProgressEntity(
    val mangaId: String,
    val chapterNumber: Float,
    val currentPage: Int,
    val totalPages: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "manga_cache")
data class MangaCacheEntity(
    @PrimaryKey val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val genresJson: String = "[]",
    val statusStr: String = "UNKNOWN",
    val typeStr: String = "UNKNOWN",
    val rating: Float? = null,
    val latestChapter: Int? = null,
    val totalChapters: Int? = null,
    val description: String = "",
    val cachedAt: Long = System.currentTimeMillis(),
    val url: String = "",
    /** JSON array of serialised Chapter objects — kept as a flat JSON string. */
    val chaptersJson: String = "[]"
)

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val chapterUrl: String,
    val chapterTitle: String? = null,
    val targetDir: String,
    val status: String = "queued", // queued|running|completed|failed|cancelled
    val progress: Float = 0f,
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val retries: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_manga")
data class DownloadedMangaEntity(
    @PrimaryKey val mangaId: String,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val localCoverPath: String? = null,
    val sourceId: String,
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val genresJson: String = "[]",
    val statusStr: String = "UNKNOWN",
    val typeStr: String = "UNKNOWN",
    val description: String = "",
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis()
)
