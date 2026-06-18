package com.exapps.mangaworld.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.exapps.mangaworld.core.data.local.dao.*
import com.exapps.mangaworld.core.data.local.entity.*

@Database(
    entities = [
        FavoriteEntity::class,
        ReadingHistoryEntity::class,
        ReadChapterEntity::class,
        ReadingProgressEntity::class,
        ReaderAnnotationEntity::class,
        MangaCacheEntity::class,
        DownloadTaskEntity::class,
        DownloadedMangaEntity::class,
    ],
    version = 9,          // v9: add indices for query performance
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readingHistoryDao(): ReadingHistoryDao
    abstract fun readChapterDao(): ReadChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun readerAnnotationDao(): ReaderAnnotationDao
    abstract fun mangaCacheDao(): MangaCacheDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadedMangaDao(): DownloadedMangaDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // reading_history: drop lastChapterId if it exists (schema drift fix)
                // SQLite doesn't support DROP COLUMN directly, so we recreate the table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_history_new (
                        mangaId TEXT NOT NULL PRIMARY KEY,
                        slug TEXT NOT NULL,
                        title TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        lastChapterNumber REAL NOT NULL,
                        lastChapterUrl TEXT NOT NULL DEFAULT '',
                        lastReadAt INTEGER NOT NULL,
                        readChapters INTEGER NOT NULL DEFAULT 0,
                        totalChapters INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("""
                    INSERT INTO reading_history_new (mangaId, slug, title, coverUrl, sourceId, lastChapterNumber, lastChapterUrl, lastReadAt, readChapters, totalChapters)
                    SELECT mangaId, slug, title, coverUrl, sourceId, lastChapterNumber, lastChapterUrl, lastReadAt, readChapters, totalChapters
                    FROM reading_history
                """)
                db.execSQL("DROP TABLE IF EXISTS reading_history")
                db.execSQL("ALTER TABLE reading_history_new RENAME TO reading_history")

                // Add indices for reading_history
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_lastReadAt ON reading_history(lastReadAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_mangaId ON reading_history(mangaId)")

                // Add indices for read_chapters
                db.execSQL("CREATE INDEX IF NOT EXISTS index_read_chapters_mangaId ON read_chapters(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_read_chapters_readAt ON read_chapters(readAt)")

                // Add indices for reading_progress
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_progress_mangaId ON reading_progress(mangaId)")

                // Add indices for download_tasks
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_mangaId ON download_tasks(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_chapterUrl ON download_tasks(chapterUrl)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_status ON download_tasks(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_updatedAt ON download_tasks(updatedAt)")

                // Add indices for manga_cache
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_cache_sourceId ON manga_cache(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_cache_cachedAt ON manga_cache(cachedAt)")
            }
        }
    }
}
