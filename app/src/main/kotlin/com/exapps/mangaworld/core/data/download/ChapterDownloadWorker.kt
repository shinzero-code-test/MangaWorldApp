package com.exapps.mangaworld.core.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val mangaId = inputData.getString(KEY_MANGA_ID) ?: return Result.failure()
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return Result.failure()
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE)
        val pages = inputData.getStringArray(KEY_PAGES)?.toList().orEmpty()
        if (pages.isEmpty()) return Result.failure()

        val chapterKey = chapterUrl.substringAfterLast("/").ifBlank { "chapter" }
        val targetDir = File(applicationContext.getExternalFilesDir(null), "downloads/$mangaId/$chapterKey")
        if (!targetDir.exists()) targetDir.mkdirs()
        downloadTaskDao.updateState(taskId, "running", 0f, 0, pages.size, System.currentTimeMillis(), null)

        val displayTitle = chapterTitle ?: "الفصل $chapterKey"
        runCatching {
            setForeground(foregroundInfo(displayTitle, 0, pages.size))
        }

        return runCatching {
            pages.forEachIndexed { index, pageUrl ->
                val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                conn.inputStream.use { input ->
                    File(targetDir, "${index + 1}.jpg").outputStream().use { out -> input.copyTo(out) }
                }
                val done = index + 1
                downloadTaskDao.updateState(taskId, "running", done.toFloat() / pages.size, done, pages.size, System.currentTimeMillis(), null)
                runCatching { setForeground(foregroundInfo(displayTitle, done, pages.size)) }
            }
            File(targetDir, ".completed").writeText("ok")
            downloadTaskDao.updateState(taskId, "completed", 1f, pages.size, pages.size, System.currentTimeMillis(), null)
            // Refresh the downloaded chapter count in the local storage table
            val count = (applicationContext.getExternalFilesDir(null)
                ?.let { java.io.File(it, "downloads/$mangaId") }
                ?.listFiles()?.count { it.isDirectory && java.io.File(it, ".completed").exists() } ?: 0)
            downloadedMangaDao.updateChapterCount(mangaId, count)
            Result.success()
        }.getOrElse { e ->
            val current = downloadTaskDao.getById(taskId)
            if (current != null) {
                downloadTaskDao.upsert(
                    current.copy(
                        status = "failed",
                        retries = current.retries + 1,
                        errorMessage = e.message,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.retry()
        }
    }

    private fun foregroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, MangaWorldApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("جاري تنزيل $title")
            .setContentText("$done/$total")
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANGA_ID = "manga_id"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_PAGES = "pages"
        private const val NOTIFICATION_ID = 1001
    }
}

