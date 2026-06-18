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
                // reading_history: drop lastChapterId if it exists
                val historyColumns = getColumns(db, "reading_history")
                if ("lastChapterId" in historyColumns) {
                    recreateTable(db, "reading_history",
                        "mangaId TEXT NOT NULL PRIMARY KEY, slug TEXT NOT NULL, title TEXT NOT NULL, coverUrl TEXT NOT NULL, sourceId TEXT NOT NULL, lastChapterNumber REAL NOT NULL, lastChapterUrl TEXT NOT NULL DEFAULT '', lastReadAt INTEGER NOT NULL, readChapters INTEGER NOT NULL DEFAULT 0, totalChapters INTEGER NOT NULL DEFAULT 0",
                        "mangaId, slug, title, coverUrl, sourceId, lastChapterNumber, lastChapterUrl, lastReadAt, readChapters, totalChapters"
                    )
                }

                // read_chapters: drop chapterId if it exists
                val chapterColumns = getColumns(db, "read_chapters")
                if ("chapterId" in chapterColumns) {
                    recreateTable(db, "read_chapters",
                        "mangaId TEXT NOT NULL, chapterNumber REAL NOT NULL, readAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(mangaId, chapterNumber)",
                        "mangaId, chapterNumber, readAt"
                    )
                }

                // Add indices
                safeCreateIndex(db, "index_reading_history_lastReadAt", "reading_history", "lastReadAt")
                safeCreateIndex(db, "index_reading_history_mangaId", "reading_history", "mangaId")
                safeCreateIndex(db, "index_read_chapters_mangaId", "read_chapters", "mangaId")
                safeCreateIndex(db, "index_read_chapters_readAt", "read_chapters", "readAt")
                safeCreateIndex(db, "index_reading_progress_mangaId", "reading_progress", "mangaId")
                safeCreateIndex(db, "index_download_tasks_mangaId", "download_tasks", "mangaId")
                safeCreateIndex(db, "index_download_tasks_chapterUrl", "download_tasks", "chapterUrl")
                safeCreateIndex(db, "index_download_tasks_status", "download_tasks", "status")
                safeCreateIndex(db, "index_download_tasks_updatedAt", "download_tasks", "updatedAt")
                safeCreateIndex(db, "index_manga_cache_sourceId", "manga_cache", "sourceId")
                safeCreateIndex(db, "index_manga_cache_cachedAt", "manga_cache", "cachedAt")
            }

            private fun getColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
                val columns = mutableSetOf<String>()
                val cursor = db.query("PRAGMA table_info($table)")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                cursor.close()
                return columns
            }

            private fun recreateTable(db: SupportSQLiteDatabase, tableName: String, columns: String, selectColumns: String) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ${tableName}_new ($columns)")
                db.execSQL("INSERT INTO ${tableName}_new ($selectColumns) SELECT $selectColumns FROM $tableName")
                db.execSQL("DROP TABLE IF EXISTS $tableName")
                db.execSQL("ALTER TABLE ${tableName}_new RENAME TO $tableName")
            }

            private fun safeCreateIndex(db: SupportSQLiteDatabase, indexName: String, table: String, column: String) {
                db.execSQL("CREATE INDEX IF NOT EXISTS $indexName ON $table($column)")
            }
        }
    }
}
