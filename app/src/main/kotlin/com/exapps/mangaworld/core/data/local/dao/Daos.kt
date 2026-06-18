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

    @Query("SELECT * FROM favorites WHERE mangaId = :mangaId LIMIT 1")
    suspend fun getById(mangaId: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    @Query("UPDATE favorites SET readChapters = :read, totalChapters = :total WHERE mangaId = :mangaId")
    suspend fun updateProgress(mangaId: String, read: Int, total: Int)

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getFavoritesList(): List<FavoriteEntity>
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

    @Query("SELECT * FROM reading_history ORDER BY lastReadAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ReadingHistoryEntity>

    @Query("SELECT * FROM reading_history ORDER BY lastReadAt DESC")
    suspend fun getAll(): List<ReadingHistoryEntity>

    @Query("SELECT * FROM reading_history ORDER BY lastReadAt DESC LIMIT 1")
    suspend fun getLatest(): ReadingHistoryEntity?
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

    @Query("SELECT COUNT(*) FROM read_chapters")
    suspend fun getTotalReadCount(): Int

    @Query("SELECT readAt FROM read_chapters ORDER BY readAt DESC LIMIT :limit")
    suspend fun getReadTimestamps(limit: Int = 365): List<Long>

    @Query("SELECT * FROM read_chapters ORDER BY readAt DESC")
    suspend fun getAll(): List<ReadChapterEntity>
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE mangaId = :mangaId AND chapterNumber = :chapter")
    suspend fun get(mangaId: String, chapter: Float): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE mangaId = :mangaId")
    suspend fun getAllForManga(mangaId: String): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ReadingProgressEntity>
}

@Dao
interface ReaderAnnotationDao {
    @Query("SELECT * FROM reader_annotations WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl ORDER BY pageIndex ASC")
    fun observeChapterAnnotations(mangaId: String, chapterUrl: String): Flow<List<ReaderAnnotationEntity>>

    @Query("SELECT * FROM reader_annotations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<ReaderAnnotationEntity>

    @Query("SELECT * FROM reader_annotations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ReaderAnnotationEntity>

    @Query("SELECT * FROM reader_annotations WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl AND pageIndex = :pageIndex LIMIT 1")
    suspend fun get(mangaId: String, chapterUrl: String, pageIndex: Int): ReaderAnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReaderAnnotationEntity)

    @Query("DELETE FROM reader_annotations WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl AND pageIndex = :pageIndex")
    suspend fun delete(mangaId: String, chapterUrl: String, pageIndex: Int)
}

@Dao
interface MangaCacheDao {
    @Query("SELECT * FROM manga_cache WHERE mangaId = :mangaId")
    suspend fun get(mangaId: String): MangaCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MangaCacheEntity)

    @Query("DELETE FROM manga_cache WHERE cachedAt < :before")
    suspend fun evictOlderThan(before: Long)

    @Query("SELECT * FROM manga_cache WHERE mangaId IN (:mangaIds)")
    suspend fun getByIds(mangaIds: List<String>): List<MangaCacheEntity>

    @Query("SELECT * FROM manga_cache ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandom(): MangaCacheEntity?
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE chapterUrl = :chapterUrl AND mangaId = :mangaId AND (status = 'queued' OR status = 'running') LIMIT 1")
    suspend fun getPendingByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE chapterUrl = :chapterUrl AND mangaId = :mangaId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity?

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

    @Query("DELETE FROM download_tasks WHERE mangaId = :mangaId")
    suspend fun deleteByMangaId(mangaId: String)
}

@Dao
interface DownloadedMangaDao {
    @Query("SELECT * FROM downloaded_manga ORDER BY lastUpdatedAt DESC")
    fun observeAll(): Flow<List<DownloadedMangaEntity>>

    @Query("SELECT * FROM downloaded_manga WHERE mangaId = :mangaId LIMIT 1")
    suspend fun get(mangaId: String): DownloadedMangaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedMangaEntity)

    @Query("UPDATE downloaded_manga SET downloadedChapters = :count, lastUpdatedAt = :now WHERE mangaId = :mangaId")
    suspend fun updateChapterCount(mangaId: String, count: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloaded_manga WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)
}
