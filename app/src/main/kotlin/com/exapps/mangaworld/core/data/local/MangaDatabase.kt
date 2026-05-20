package com.exapps.mangaworld.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 7,          // v7: enriched download task metadata for retries
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
}
