package com.exapps.mangaworld.core.data.download

import android.app.Application
import androidx.work.*
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.domain.model.ChapterPage
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueManager @Inject constructor(
    private val app: Application,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: DownloadedMangaDao
) {
    // ─── Observe ──────────────────────────────────────────────────────────────

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()
    fun observeDownloadedMangas(): Flow<List<DownloadedMangaEntity>> = downloadedMangaDao.observeAll()

    // ─── Chapter key (trim trailing slash so WordPress URLs work) ─────────────

    private fun chapterKey(chapterUrl: String): String =
        chapterUrl.trimEnd('/').substringAfterLast("/").ifBlank { "chapter" }

    private fun chapterDir(mangaId: String, chapterUrl: String): File =
        File(app.getExternalFilesDir(null), "downloads/$mangaId/${chapterKey(chapterUrl)}")

    private fun mangaDir(mangaId: String): File =
        File(app.getExternalFilesDir(null), "downloads/$mangaId")

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun isChapterDownloaded(mangaId: String, chapterUrl: String): Boolean {
        val dir = chapterDir(mangaId, chapterUrl)
        if (!dir.exists() || !File(dir, ".completed").exists()) return false
        return dir.listFiles()?.any { it.isFile && it.extension.lowercase() in setOf("jpg", "png", "webp") } == true
    }

    fun getLocalChapterPages(mangaId: String, chapterUrl: String): List<ChapterPage> {
        val dir = chapterDir(mangaId, chapterUrl)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "png", "webp") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapIndexed { index, file -> ChapterPage(index = index, url = file.toURI().toString()) }
            ?: emptyList()
    }

    /** Count locally-downloaded chapters for a manga by scanning the directory. */
    fun countDownloadedChapters(mangaId: String): Int {
        val dir = mangaDir(mangaId)
        if (!dir.exists()) return 0
        return dir.listFiles()?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0
    }

    // ─── Enqueue ──────────────────────────────────────────────────────────────

    suspend fun enqueueAndRun(
        taskId: String,
        mangaId: String,
        chapterUrl: String,
        chapterTitle: String?,
        pages: List<ChapterPage>,
        wifiOnly: Boolean = true,
        mangaMetadata: DownloadedMangaEntity? = null
    ) {
        if (downloadTaskDao.getPendingByChapter(chapterUrl, mangaId) != null) return

        val targetDir = chapterDir(mangaId, chapterUrl)
        downloadTaskDao.upsert(
            DownloadTaskEntity(
                id = taskId,
                mangaId = mangaId,
                chapterUrl = chapterUrl,
                chapterTitle = chapterTitle,
                targetDir = targetDir.absolutePath,
                status = "queued"
            )
        )

        // Persist manga metadata so the Local Storage screen can display it
        if (mangaMetadata != null) {
            val existing = downloadedMangaDao.get(mangaId)
            downloadedMangaDao.upsert(
                mangaMetadata.copy(
                    downloadedChapters = existing?.downloadedChapters ?: 0,
                    downloadedAt = existing?.downloadedAt ?: System.currentTimeMillis()
                )
            )
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(ChapterDownloadWorker.KEY_TASK_ID, taskId)
            .putString(ChapterDownloadWorker.KEY_MANGA_ID, mangaId)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, chapterUrl)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, chapterTitle)
            .putStringArray(ChapterDownloadWorker.KEY_PAGES, pages.map { it.url }.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .addTag(taskId)
            .addTag("manga_$mangaId")
            .build()
        WorkManager.getInstance(app).enqueue(request)
    }

    // ─── Cancel / delete ─────────────────────────────────────────────────────

    suspend fun cancelTask(taskId: String) {
        val task = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.upsert(
            task.copy(status = "cancelled", updatedAt = System.currentTimeMillis(),
                errorMessage = "Cancelled by user")
        )
        chapterDir(task.mangaId, task.chapterUrl).deleteRecursively()
        WorkManager.getInstance(app).cancelAllWorkByTag(taskId)
    }

    suspend fun retryTask(taskId: String) {
        val task = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.upsert(task.copy(status = "queued", errorMessage = null,
            updatedAt = System.currentTimeMillis()))
        WorkManager.getInstance(app).enqueue(
            OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .setInputData(Data.Builder()
                    .putString(ChapterDownloadWorker.KEY_TASK_ID, task.id)
                    .putString(ChapterDownloadWorker.KEY_MANGA_ID, task.mangaId)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, task.chapterUrl)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, task.chapterTitle)
                    .build())
                .addTag(taskId)
                .build()
        )
    }

    suspend fun clearCompleted() = downloadTaskDao.clearCompleted()

    /**
     * Delete ALL downloaded content for a manga: files on disk, task records,
     * and the downloaded_manga metadata row.
     */
    suspend fun deleteDownloadedManga(mangaId: String) {
        // Cancel any active work
        WorkManager.getInstance(app).cancelAllWorkByTag("manga_$mangaId")
        // Delete files
        mangaDir(mangaId).deleteRecursively()
        // Remove DB records
        downloadTaskDao.deleteByMangaId(mangaId)
        downloadedMangaDao.delete(mangaId)
    }

    /** Update the chapter count in the downloaded_manga table after a chapter completes. */
    suspend fun refreshDownloadedCount(mangaId: String) {
        val count = countDownloadedChapters(mangaId)
        downloadedMangaDao.updateChapterCount(mangaId, count)
    }
}
