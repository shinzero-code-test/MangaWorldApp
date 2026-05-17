package com.exapps.mangaworld.core.data.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: DownloadedMangaDao,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId      = inputData.getString(KEY_TASK_ID)      ?: return@withContext Result.failure()
        val mangaId     = inputData.getString(KEY_MANGA_ID)      ?: return@withContext Result.failure()
        val chapterUrl  = inputData.getString(KEY_CHAPTER_URL)   ?: return@withContext Result.failure()
        val chapterTitle= inputData.getString(KEY_CHAPTER_TITLE)
        val referer     = inputData.getString(KEY_REFERER)       ?: ""
        val pages       = inputData.getStringArray(KEY_PAGES)?.toList().orEmpty()
        if (pages.isEmpty()) return@withContext Result.failure()

        val chapterKey  = chapterUrl.trimEnd('/').substringAfterLast("/").ifBlank { "chapter" }
        val mangaDir    = File(appContext.getExternalFilesDir(null), "downloads/${sanitizeName(mangaId)}")
        val targetDir   = File(mangaDir, chapterKey)
        if (!targetDir.exists()) targetDir.mkdirs()

        val displayTitle = chapterTitle ?: "الفصل $chapterKey"
        downloadTaskDao.updateState(taskId, "running", 0f, 0, pages.size, System.currentTimeMillis(), null)

        runCatching { setForeground(buildForegroundInfo(displayTitle, 0, pages.size)) }

        return@withContext runCatching {
            pages.forEachIndexed { index, pageUrl ->
                downloadPage(pageUrl, referer, File(targetDir, "${index + 1}.jpg"))
                val done = index + 1
                downloadTaskDao.updateState(
                    taskId, "running",
                    done.toFloat() / pages.size, done, pages.size,
                    System.currentTimeMillis(), null
                )
                runCatching { setForeground(buildForegroundInfo(displayTitle, done, pages.size)) }
            }

            // Mark chapter complete
            File(targetDir, ".completed").writeText("ok")
            downloadTaskDao.updateState(taskId, "completed", 1f, pages.size, pages.size,
                System.currentTimeMillis(), null)

            // Refresh count in downloaded_manga table
            val count = mangaDir.listFiles()
                ?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0
            downloadedMangaDao.updateChapterCount(mangaId, count)

            // Show completion notification
            showCompletionNotification(displayTitle, pages.size)

            Result.success()
        }.getOrElse { e ->
            runCatching {
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
                showFailureNotification(displayTitle)
            }
            Result.retry()
        }
    }

    // ─── Image download with Referer header ───────────────────────────────────

    private fun downloadPage(pageUrl: String, referer: String, outFile: File) {
        val reqBuilder = Request.Builder()
            .url(pageUrl)
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
        if (referer.isNotBlank()) reqBuilder.header("Referer", referer)

        val resp = okHttpClient.newCall(reqBuilder.build()).execute()
        val body = resp.body ?: run { resp.close(); error("Empty body for $pageUrl") }
        body.byteStream().use { input ->
            outFile.outputStream().use { out -> input.copyTo(out) }
        }
        resp.close()
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun buildForegroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, MangaWorldApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("جاري تنزيل $title")
            .setContentText(if (total > 0) "$done/$total صفحة" else "جاري...")
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_PROGRESS, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_PROGRESS, notification)
        }
    }

    private fun showCompletionNotification(title: String, pages: Int) {
        val notif = NotificationCompat.Builder(appContext, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✓ تم تنزيل $title")
            .setContentText("$pages صفحة — محفوظ للقراءة بدون إنترنت")
            .setAutoCancel(true)
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_COMPLETE + System.currentTimeMillis().toInt() % 10000, notif)
    }

    private fun showFailureNotification(title: String) {
        val notif = NotificationCompat.Builder(appContext, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("✗ فشل تنزيل $title")
            .setContentText("سيتم إعادة المحاولة تلقائياً")
            .setAutoCancel(true)
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_FAIL + System.currentTimeMillis().toInt() % 10000, notif)
    }

    companion object {
        const val KEY_TASK_ID      = "task_id"
        const val KEY_MANGA_ID     = "manga_id"
        const val KEY_CHAPTER_URL  = "chapter_url"
        const val KEY_CHAPTER_TITLE= "chapter_title"
        const val KEY_PAGES        = "pages"
        const val KEY_REFERER      = "referer"
        private const val NOTIF_ID_PROGRESS = 1001
        private const val NOTIF_ID_COMPLETE = 2000
        private const val NOTIF_ID_FAIL     = 3000

        fun sanitizeName(name: String): String =
            name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
    }
}
