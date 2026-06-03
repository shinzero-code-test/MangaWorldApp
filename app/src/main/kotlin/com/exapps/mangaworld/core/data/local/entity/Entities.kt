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
    val lastChapterId: String = "",
    val lastChapterUrl: String = "",
    val lastReadAt: Long,
    val readChapters: Int = 0,
    val totalChapters: Int = 0
) {
    fun toDomain() = ReadingHistoryItem(
        mangaId = mangaId, slug = slug, title = title, coverUrl = coverUrl,
        source = MangaSource.fromId(sourceId), lastChapterNumber = lastChapterNumber,
        lastChapterUrl = lastChapterUrl,
        lastReadAt = lastReadAt, readChapters = readChapters, totalChapters = totalChapters
    )
}

@Entity(tableName = "read_chapters", primaryKeys = ["mangaId", "chapterId"])
data class ReadChapterEntity(
    val mangaId: String,
    val chapterId: String,
    val chapterNumber: Float,
    val readAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_progress", primaryKeys = ["mangaId", "chapterId"])
data class ReadingProgressEntity(
    val mangaId: String,
    val chapterId: String,
    val chapterNumber: Float,
    val currentPage: Int,
    val totalPages: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reader_annotations", primaryKeys = ["mangaId", "chapterUrl", "pageIndex"])
data class ReaderAnnotationEntity(
    val mangaId: String,
    val chapterUrl: String,
    val pageIndex: Int,
    val note: String = "",
    val isBookmarked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = ReaderPageAnnotation(
        pageIndex = pageIndex,
        note = note.ifBlank { null },
        isBookmarked = isBookmarked,
        updatedAt = updatedAt
    )
}

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
    val mangaTitle: String? = null,
    val chapterUrl: String,
    val chapterTitle: String? = null,
    val targetDir: String,
    val referer: String = "",
    val pagesJson: String = "[]",
    val chapterId: String = "",
    val chapterNumber: Float? = null,
    val priority: Int = 0,
    val bandwidthCapKb: Int = 0,
    val status: String = "queued", // queued|running|paused|completed|failed|cancelled
    val progress: Float = 0f,
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val retries: Int = 0,
    val nextRetryAt: Long? = null,
    val integrityStatus: String = "unknown",
    val integrityMessage: String? = null,
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


@Entity(tableName = "source_browse_metadata")
data class SourceBrowseMetadataEntity(
    @PrimaryKey val sourceId: String,
    val genresJson: String = "[]",
    val statusesJson: String = "[]",
    val typesJson: String = "[]",
    val categoriesJson: String = "[]",
    val refreshedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = SourceBrowseMetadata(
        source = MangaSource.fromId(sourceId),
        genres = jsonList(genresJson),
        statuses = jsonList(statusesJson).mapNotNull { name -> MangaStatus.values().firstOrNull { it.name == name } },
        types = jsonList(typesJson).mapNotNull { name -> MangaType.values().firstOrNull { it.name == name } },
        categories = jsonList(categoriesJson),
        refreshedAt = refreshedAt
    )

    companion object {
        private fun jsonList(raw: String): List<String> = runCatching {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}

fun SourceBrowseMetadata.toEntity() = SourceBrowseMetadataEntity(
    sourceId = source.id,
    genresJson = org.json.JSONArray(genres).toString(),
    statusesJson = org.json.JSONArray(statuses.map { it.name }).toString(),
    typesJson = org.json.JSONArray(types.map { it.name }).toString(),
    categoriesJson = org.json.JSONArray(categories).toString(),
    refreshedAt = refreshedAt
)
