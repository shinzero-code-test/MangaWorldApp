package com.exapps.mangaworld.core.data.local.dao

import androidx.room.*
import com.exapps.mangaworld.core.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mangaId = :mangaId AND isFavorite = 1)")
    fun isFavoriteFlow(mangaId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mangaId = :mangaId AND isFavorite = 1)")
    suspend fun isFavorite(mangaId: String): Boolean

    @Query("SELECT * FROM favorites WHERE mangaId = :mangaId LIMIT 1")
    suspend fun getById(mangaId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE mangaId = :mangaId LIMIT 1")
    fun observeById(mangaId: String): Flow<FavoriteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    @Query("UPDATE favorites SET isFavorite = :isFav, addedAt = :updatedAt WHERE mangaId = :mangaId")
    suspend fun setFavorite(
        mangaId: String,
        isFav: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE favorites SET isFavorite = 1 WHERE mangaId = :mangaId")
    suspend fun restoreFavorite(mangaId: String)

    @Query("DELETE FROM favorites WHERE mangaId = :mangaId AND addedAt <= :olderThan")
    suspend fun deleteIfOlder(mangaId: String, olderThan: Long)

    @Query("UPDATE favorites SET readChapters = :read, totalChapters = :total WHERE mangaId = :mangaId")
    suspend fun updateProgress(mangaId: String, read: Int, total: Int)

    @Query("UPDATE favorites SET readingStatus = :status, addedAt = :updatedAt WHERE mangaId = :mangaId")
    suspend fun updateReadingStatus(
        mangaId: String,
        status: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM favorites WHERE readingStatus = :status ORDER BY addedAt DESC")
    suspend fun getByStatus(status: String): List<FavoriteEntity>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllLibraryEntries(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE isFavorite = 1 ORDER BY addedAt DESC")
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

    /** Atomically delete only if the entity's lastReadAt is older than the given timestamp. */
    @Query("DELETE FROM reading_history WHERE mangaId = :mangaId AND lastReadAt <= :olderThan")
    suspend fun deleteIfOlder(mangaId: String, olderThan: Long)

    @Query("SELECT mangaId FROM reading_history")
    suspend fun getAllMangaIds(): List<String>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markRead(entity: ReadChapterEntity)

    /** Single-statement batch insert — used by mark-all-read on long manga. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markAllRead(entities: List<ReadChapterEntity>)

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

    /** Atomically delete annotation only if its updatedAt is older than the given timestamp. */
    @Query("DELETE FROM reader_annotations WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl AND pageIndex = :pageIndex AND updatedAt <= :olderThan")
    suspend fun deleteIfOlder(mangaId: String, chapterUrl: String, pageIndex: Int, olderThan: Long)
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

    @Query("SELECT * FROM manga_cache ORDER BY cachedAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 200): List<MangaCacheEntity>
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE mangaId = :mangaId AND status IN ('queued', 'running', 'paused') ORDER BY createdAt ASC, id ASC")
    suspend fun getIncompleteByMangaId(mangaId: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE mangaId = :mangaId AND status = 'queued' ORDER BY createdAt ASC, id ASC")
    suspend fun getQueuedByMangaId(mangaId: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('queued', 'running', 'paused') ORDER BY createdAt ASC, id ASC")
    suspend fun getAllIncomplete(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'paused' ORDER BY createdAt ASC, id ASC")
    suspend fun getAllPaused(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE chapterUrl = :chapterUrl AND mangaId = :mangaId AND (status = 'queued' OR status = 'running' OR status = 'paused') LIMIT 1")
    suspend fun getPendingByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE chapterUrl = :chapterUrl AND mangaId = :mangaId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<DownloadTaskEntity>)

    @Query("""
        UPDATE download_tasks
        SET status = :status,
            progress = :progress,
            downloadedPages = :downloadedPages,
            totalPages = :totalPages,
            updatedAt = :updatedAt,
            errorMessage = :errorMessage
        WHERE id = :id AND status IN ('queued', 'running')
    """)
    suspend fun updateStateIfActive(
        id: String,
        status: String,
        progress: Float,
        downloadedPages: Int,
        totalPages: Int,
        updatedAt: Long,
        errorMessage: String?
    ): Int

    @Query("""
        UPDATE download_tasks
        SET status = :status,
            retries = :retries,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status IN ('queued', 'running')
    """)
    suspend fun updateFailureStateIfActive(
        id: String,
        status: String,
        retries: Int,
        errorMessage: String,
        updatedAt: Long
    ): Int

    @Query("UPDATE download_tasks SET failureNotified = 1, updatedAt = :updatedAt WHERE id = :id AND failureNotified = 0")
    suspend fun markFailureNotified(id: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        DELETE FROM download_tasks
        WHERE (
            status = 'completed'
            AND (batchId IS NULL OR batchId IN (SELECT id FROM download_batches WHERE completionNotified = 1))
        ) OR (
            status IN ('failed', 'cancelled')
            AND (
                (batchId IS NOT NULL AND batchId IN (SELECT id FROM download_batches WHERE completionNotified = 1))
                OR (batchId IS NULL AND failureNotified = 1)
            )
        )
    """)
    suspend fun clearCompleted()

    @Query("DELETE FROM download_tasks WHERE id IN (:ids) AND status IN ('completed', 'failed', 'cancelled')")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM download_tasks WHERE mangaId = :mangaId")
    suspend fun deleteByMangaId(mangaId: String)
}

@Dao
interface DownloadBatchDao {
    @Query("SELECT * FROM download_batches WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadBatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(batch: DownloadBatchEntity)

    @Query("""
        UPDATE download_batches
        SET completedChapters = (
                SELECT COUNT(*) FROM download_tasks
                WHERE batchId = :id AND status = 'completed'
            ),
            failedChapters = (
                SELECT COUNT(*) FROM download_tasks
                WHERE batchId = :id AND status IN ('failed', 'cancelled')
            ),
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun synchronizeOutcomeCounts(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE download_batches
        SET completionNotified = 1, updatedAt = :updatedAt
        WHERE id = :id
          AND completionNotified = 0
          AND completedChapters + failedChapters >= totalChapters
    """)
    suspend fun claimTerminalNotification(id: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM download_batches WHERE mangaId = :mangaId")
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

    /** Targeted cover-path update that cannot clobber concurrently-changed counters. */
    @Query("UPDATE downloaded_manga SET localCoverPath = :path WHERE mangaId = :mangaId")
    suspend fun updateCoverPath(mangaId: String, path: String)

    @Query("SELECT * FROM downloaded_manga")
    suspend fun getAll(): List<DownloadedMangaEntity>

    @Query("DELETE FROM downloaded_manga WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)
}
