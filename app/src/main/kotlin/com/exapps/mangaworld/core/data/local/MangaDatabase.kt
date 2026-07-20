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
    version = 12,         // v12: add durationMs to reading_history
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
                // Recreate reading_history to remove stale lastChapterId column
                val historyCols = getColumnNames(db, "reading_history")
                if ("lastChapterId" in historyCols) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS reading_history_new (mangaId TEXT NOT NULL PRIMARY KEY, slug TEXT NOT NULL, title TEXT NOT NULL, coverUrl TEXT NOT NULL, sourceId TEXT NOT NULL, lastChapterNumber REAL NOT NULL, lastChapterUrl TEXT NOT NULL DEFAULT '', lastReadAt INTEGER NOT NULL, readChapters INTEGER NOT NULL DEFAULT 0, totalChapters INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("INSERT INTO reading_history_new (mangaId, slug, title, coverUrl, sourceId, lastChapterNumber, lastChapterUrl, lastReadAt, readChapters, totalChapters) SELECT mangaId, slug, title, coverUrl, sourceId, lastChapterNumber, lastChapterUrl, lastReadAt, readChapters, totalChapters FROM reading_history")
                    db.execSQL("DROP TABLE reading_history")
                    db.execSQL("ALTER TABLE reading_history_new RENAME TO reading_history")
                }
                // Recreate read_chapters to remove stale chapterId column
                val chapterCols = getColumnNames(db, "read_chapters")
                if ("chapterId" in chapterCols) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS read_chapters_new (mangaId TEXT NOT NULL, chapterNumber REAL NOT NULL, readAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(mangaId, chapterNumber))")
                    db.execSQL("INSERT INTO read_chapters_new (mangaId, chapterNumber, readAt) SELECT mangaId, chapterNumber, readAt FROM read_chapters")
                    db.execSQL("DROP TABLE read_chapters")
                    db.execSQL("ALTER TABLE read_chapters_new RENAME TO read_chapters")
                }
                addAllIndices(db)
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Verify and add any missing indices
                addAllIndices(db)
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN readingStatus TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getColumnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
            val cols = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($table)")
            while (cursor.moveToNext()) cols.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            cursor.close()
            return cols
        }

        private fun addAllIndices(db: SupportSQLiteDatabase) {
            listOf(
                "index_reading_history_lastReadAt" to "reading_history(lastReadAt)",
                "index_reading_history_mangaId" to "reading_history(mangaId)",
                "index_read_chapters_mangaId" to "read_chapters(mangaId)",
                "index_read_chapters_readAt" to "read_chapters(readAt)",
                "index_reading_progress_mangaId" to "reading_progress(mangaId)",
                "index_download_tasks_mangaId" to "download_tasks(mangaId)",
                "index_download_tasks_chapterUrl" to "download_tasks(chapterUrl)",
                "index_download_tasks_status" to "download_tasks(status)",
                "index_download_tasks_updatedAt" to "download_tasks(updatedAt)",
                "index_manga_cache_sourceId" to "manga_cache(sourceId)",
                "index_manga_cache_cachedAt" to "manga_cache(cachedAt)"
            ).forEach { (name, def) -> db.execSQL("CREATE INDEX IF NOT EXISTS $name ON $def") }
        }
    }
}
