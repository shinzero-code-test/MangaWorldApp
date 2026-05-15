package com.exapps.mangaworld.core.data.local.dao

import androidx.room.*
import com.exapps.mangaworld.core.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mangaId = :mangaId)")
    fun isFavoriteFlow(mangaId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mangaId = :mangaId)")
    suspend fun isFavorite(mangaId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    @Query("UPDATE favorites SET readChapters = :read, totalChapters = :total WHERE mangaId = :mangaId")
    suspend fun updateProgress(mangaId: String, read: Int, total: Int)
}

@Dao
interface ReadingHistoryDao {
    @Query("SELECT * FROM reading_history ORDER BY lastReadAt DESC")
    fun getAllHistory(): Flow<List<ReadingHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    @Query("DELETE FROM reading_history")
    suspend fun clearAll()

    @Query("SELECT * FROM reading_history WHERE mangaId = :mangaId")
    suspend fun getByMangaId(mangaId: String): ReadingHistoryEntity?
}

@Dao
interface ReadChapterDao {
    @Query("SELECT chapterNumber FROM read_chapters WHERE mangaId = :mangaId")
    fun getReadChapters(mangaId: String): Flow<List<Float>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(entity: ReadChapterEntity)

    @Query("DELETE FROM read_chapters WHERE mangaId = :mangaId AND chapterNumber = :chapterNumber")
    suspend fun markUnread(mangaId: String, chapterNumber: Float)

    @Query("SELECT EXISTS(SELECT 1 FROM read_chapters WHERE mangaId = :mangaId AND chapterNumber = :chapterNumber)")
    suspend fun isRead(mangaId: String, chapterNumber: Float): Boolean
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE mangaId = :mangaId AND chapterNumber = :chapter")
    suspend fun get(mangaId: String, chapter: Float): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE mangaId = :mangaId")
    suspend fun getAllForManga(mangaId: String): List<ReadingProgressEntity>
}

@Dao
interface MangaCacheDao {
    @Query("SELECT * FROM manga_cache WHERE mangaId = :mangaId")
    suspend fun get(mangaId: String): MangaCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MangaCacheEntity)

    @Query("DELETE FROM manga_cache WHERE cachedAt < :before")
    suspend fun evictOlderThan(before: Long)
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE chapterUrl = :chapterUrl AND mangaId = :mangaId AND (status = 'queued' OR status = 'running') LIMIT 1")
    suspend fun getPendingByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("""
        UPDATE download_tasks
        SET status = :status,
            progress = :progress,
            downloadedPages = :downloadedPages,
            totalPages = :totalPages,
            updatedAt = :updatedAt,
            errorMessage = :errorMessage
        WHERE id = :id
    """)
    suspend fun updateState(
        id: String,
        status: String,
        progress: Float,
        downloadedPages: Int,
        totalPages: Int,
        updatedAt: Long,
        errorMessage: String?
    )

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'completed'")
    suspend fun clearCompleted()
}
