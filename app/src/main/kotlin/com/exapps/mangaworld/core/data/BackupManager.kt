package com.exapps.mangaworld.core.data

import android.content.Context
import com.exapps.mangaworld.core.data.local.MangaDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupSection(val entryName: String) {
    FAVORITES("favorites"),
    HISTORY("history"),
    SETTINGS("settings"),
    COLLECTIONS("collections"),
    ANNOTATIONS("annotations"),
    DOWNLOADS("downloads")
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase
) {
    suspend fun createBackup(outputFile: File) = withContext(Dispatchers.IO) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            // Favorites
            writeJsonArray(zip, BackupSection.FAVORITES) {
                val favorites = database.favoriteDao().getFavoritesList()
                JSONArray().apply {
                    favorites.forEach { fav ->
                        put(JSONObject().apply {
                            put("mangaId", fav.mangaId)
                            put("slug", fav.slug)
                            put("title", fav.title)
                            put("coverUrl", fav.coverUrl)
                            put("sourceId", fav.sourceId)
                            put("addedAt", fav.addedAt)
                        })
                    }
                }.toString()
            }

            // History
            writeJsonArray(zip, BackupSection.HISTORY) {
                val history = database.readingHistoryDao().getAll()
                JSONArray().apply {
                    history.forEach { item ->
                        put(JSONObject().apply {
                            put("mangaId", item.mangaId)
                            put("slug", item.slug)
                            put("title", item.title)
                            put("coverUrl", item.coverUrl)
                            put("sourceId", item.sourceId)
                            put("lastChapterNumber", item.lastChapterNumber.toDouble())
                            put("lastReadAt", item.lastReadAt)
                        })
                    }
                }.toString()
            }

            // Settings (placeholder)
            writeJsonArray(zip, BackupSection.SETTINGS) {
                JSONObject().apply {
                    put("version", 1)
                    put("exportedAt", System.currentTimeMillis())
                }.toString()
            }

            // Collections (placeholder)
            writeJsonArray(zip, BackupSection.COLLECTIONS) {
                JSONArray().toString()
            }

            // Annotations
            writeJsonArray(zip, BackupSection.ANNOTATIONS) {
                val annotations = database.readerAnnotationDao().getAll()
                JSONArray().apply {
                    annotations.forEach { ann ->
                        put(JSONObject().apply {
                            put("mangaId", ann.mangaId)
                            put("chapterUrl", ann.chapterUrl)
                            put("pageIndex", ann.pageIndex)
                            put("note", ann.note ?: "")
                            put("isBookmarked", ann.isBookmarked)
                            put("updatedAt", ann.updatedAt)
                        })
                    }
                }.toString()
            }

            // Downloads metadata
            writeJsonArray(zip, BackupSection.DOWNLOADS) {
                val downloads = database.downloadedMangaDao().observeAll()
                JSONArray().toString()
            }
        }
    }

    suspend fun restoreBackup(inputFile: File) = withContext(Dispatchers.IO) {
        ZipInputStream(FileInputStream(inputFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val section = BackupSection.entries.find { it.entryName == entry.name }
                if (section != null) {
                    val data = zip.readBytes().decodeToString()
                    restoreSection(section, data)
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun restoreSection(section: BackupSection, data: String) {
        when (section) {
            BackupSection.FAVORITES -> {
                val arr = JSONArray(data)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    // Restore favorites
                }
            }
            BackupSection.HISTORY -> {
                val arr = JSONArray(data)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    // Restore history
                }
            }
            BackupSection.SETTINGS -> {
                // Restore settings
            }
            BackupSection.COLLECTIONS -> {
                // Restore collections
            }
            BackupSection.ANNOTATIONS -> {
                val arr = JSONArray(data)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    // Restore annotations
                }
            }
            BackupSection.DOWNLOADS -> {
                // Restore downloads metadata
            }
        }
    }

    private suspend fun writeJsonArray(
        zip: ZipOutputStream,
        section: BackupSection,
        data: () -> String
    ) {
        zip.putNextEntry(ZipEntry(section.entryName))
        zip.write(data().toByteArray())
        zip.closeEntry()
    }
}
