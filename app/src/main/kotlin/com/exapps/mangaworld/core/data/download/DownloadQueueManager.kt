package com.exapps.mangaworld.core.data.download

import android.app.Application
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.domain.model.ChapterPage
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueManager @Inject constructor(
    private val app: Application,
    private val downloadTaskDao: DownloadTaskDao
) {
    fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()

    suspend fun cancelTask(taskId: String) {
        val current = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.upsert(
            current.copy(
                status = "cancelled",
                updatedAt = System.currentTimeMillis(),
                errorMessage = "Cancelled by user"
            )
        )
        val chapterDir = File(current.targetDir)
        if (chapterDir.exists()) {
            chapterDir.listFiles()?.forEach { it.delete() }
            chapterDir.delete()
        }
        WorkManager.getInstance(app).cancelAllWorkByTag(taskId)
    }

    suspend fun clearCompleted() = downloadTaskDao.clearCompleted()

    fun isChapterDownloaded(mangaId: String, chapterUrl: String): Boolean {
        val chapterKey = chapterUrl.substringAfterLast("/").ifBlank { "chapter" }
        val dir = File(app.getExternalFilesDir(null), "downloads/$mangaId/$chapterKey")
        if (!dir.exists()) return false
        if (!File(dir, ".completed").exists()) return false
        val files = dir.listFiles()?.filter {
            it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("png", true) || it.extension.equals("webp", true))
        } ?: return false
        return files.isNotEmpty()
    }

    fun getLocalChapterPages(mangaId: String, chapterUrl: String): List<ChapterPage> {
        val chapterKey = chapterUrl.substringAfterLast("/").ifBlank { "chapter" }
        val dir = File(app.getExternalFilesDir(null), "downloads/$mangaId/$chapterKey")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("png", true) || it.extension.equals("webp", true)) }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapIndexed { index, file ->
                ChapterPage(index = index, url = file.toURI().toString())
            }
            ?: emptyList()
    }

    suspend fun enqueueAndRun(
        taskId: String,
        mangaId: String,
        chapterUrl: String,
        chapterTitle: String?,
        pages: List<ChapterPage>,
        wifiOnly: Boolean = true
    ) {
        val existing = downloadTaskDao.getPendingByChapter(chapterUrl, mangaId)
        if (existing != null) return

        val chapterKey = chapterUrl.substringAfterLast("/").ifBlank { "chapter" }
        val targetDir = File(app.getExternalFilesDir(null), "downloads/$mangaId/$chapterKey")
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
            .build()
        WorkManager.getInstance(app).enqueue(request)
    }
}
