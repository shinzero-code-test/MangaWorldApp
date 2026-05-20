package com.exapps.mangaworld.core.data.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val mangaId = inputData.getString(KEY_MANGA_ID) ?: return@withContext Result.failure()
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return@withContext Result.failure()
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE)
        val referer = inputData.getString(KEY_REFERER).orEmpty()
        val targetDirPath = inputData.getString(KEY_TARGET_DIR)
        val pages = inputData.getStringArray(KEY_PAGES)?.toList().orEmpty()
        if (pages.isEmpty()) return@withContext Result.failure()

        val targetDir = targetDirPath?.let(::File)
            ?: DownloadStorage.canonicalChapterDir(File(appContext.getExternalFilesDir(null), "downloads"), mangaId, chapterUrl)
        val mangaDir = targetDir.parentFile ?: return@withContext Result.failure()
        if (!targetDir.exists()) targetDir.mkdirs()

        val chapterKey = DownloadStorage.chapterKey(chapterUrl)
        val displayTitle = chapterTitle ?: "الفصل $chapterKey"
        downloadTaskDao.updateState(taskId, "running", 0f, 0, pages.size, System.currentTimeMillis(), null)
        runCatching { setForeground(buildForegroundInfo(displayTitle, 0, pages.size, mangaId, chapterUrl)) }

        return@withContext runCatching {
            var done = existingPageCount(targetDir)
            updateProgress(taskId, displayTitle, done, pages.size, mangaId, chapterUrl)

            val targets = pages.mapIndexed { index, pageUrl -> pageUrl to File(targetDir, "${index + 1}.jpg") }
            targets.chunked(PARALLEL_DOWNLOADS).forEach { chunk ->
                coroutineScope {
                    chunk.map { (pageUrl, outFile) ->
                        async {
                            ensureActive()
                            downloadPage(pageUrl, referer, outFile)
                        }
                    }.awaitAll().forEach { downloaded ->
                        if (downloaded) done += 1
                    }
                }
                updateProgress(taskId, displayTitle, done, pages.size, mangaId, chapterUrl)
            }

            File(targetDir, ".completed").writeText("ok")
            downloadTaskDao.updateState(taskId, "completed", 1f, pages.size, pages.size, System.currentTimeMillis(), null)

            val count = mangaDir.listFiles()?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0
            downloadedMangaDao.updateChapterCount(mangaId, count)

            showCompletionNotification(displayTitle, pages.size, mangaId, chapterUrl)
            Result.success()
        }.getOrElse { e ->
            runCatching {
                downloadTaskDao.getById(taskId)?.let { current ->
                    downloadTaskDao.upsert(
                        current.copy(
                            status = "failed",
                            retries = current.retries + 1,
                            errorMessage = e.message,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                showFailureNotification(displayTitle, mangaId, chapterUrl)
            }
            Result.retry()
        }
    }

    private suspend fun downloadPage(pageUrl: String, referer: String, outFile: File): Boolean {
        if (outFile.exists() && outFile.length() > 0L) return false

        val reqBuilder = Request.Builder()
            .url(pageUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
        if (referer.isNotBlank()) reqBuilder.header("Referer", referer)
        runCatching { resolveCookieForUrl(settingsRepository, pageUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { reqBuilder.header("Cookie", it) }

        val tempFile = File(outFile.absolutePath + ".part")
        if (tempFile.exists()) tempFile.delete()
        val resp = okHttpClient.newCall(reqBuilder.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            error("HTTP $code for $pageUrl")
        }
        val body = resp.body ?: run { resp.close(); error("Empty body for $pageUrl") }
        body.byteStream().use { input ->
            tempFile.outputStream().use { out -> input.copyTo(out) }
        }
        resp.close()
        if (tempFile.length() <= 0L) {
            tempFile.delete()
            error("Downloaded empty image for $pageUrl")
        }
        tempFile.renameTo(outFile)
        return true
    }

    private fun buildForegroundInfo(title: String, done: Int, total: Int, mangaId: String, chapterUrl: String): ForegroundInfo {
        val sourceId = mangaId.substringBefore('_')
        val notification = NotificationCompat.Builder(appContext, MangaWorldApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("جاري تنزيل $title")
            .setContentText(if (total > 0) "$done/$total صفحة" else "جاري...")
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    (mangaId + chapterUrl + "progress").hashCode(),
                    AppLaunchIntents.reader(appContext, sourceId, mangaId, chapterUrl),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_PROGRESS, notification)
        }
    }

    private fun showCompletionNotification(title: String, pages: Int, mangaId: String, chapterUrl: String) {
        val sourceId = mangaId.substringBefore('_')
        val notif = NotificationCompat.Builder(appContext, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✓ تم تنزيل $title")
            .setContentText("$pages صفحة — اضغط للقراءة بدون إنترنت")
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    (mangaId + chapterUrl + "complete").hashCode(),
                    AppLaunchIntents.reader(appContext, sourceId, mangaId, chapterUrl),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_COMPLETE + System.currentTimeMillis().toInt() % 10000, notif)
    }

    private fun showFailureNotification(title: String, mangaId: String, chapterUrl: String) {
        val notif = NotificationCompat.Builder(appContext, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("✗ فشل تنزيل $title")
            .setContentText("اضغط لفتح إدارة التنزيلات أو إعادة المحاولة")
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    (mangaId + chapterUrl + "failed").hashCode(),
                    AppLaunchIntents.downloads(appContext),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_FAIL + System.currentTimeMillis().toInt() % 10000, notif)
    }

    private fun existingPageCount(dir: File): Int =
        dir.listFiles()?.count { it.isFile && it.extension.lowercase() == "jpg" && it.length() > 0L } ?: 0

    private suspend fun updateProgress(taskId: String, title: String, done: Int, total: Int, mangaId: String, chapterUrl: String) {
        downloadTaskDao.updateState(
            taskId,
            "running",
            if (total == 0) 0f else done.toFloat() / total,
            done,
            total,
            System.currentTimeMillis(),
            null
        )
        runCatching { setForeground(buildForegroundInfo(title, done, total, mangaId, chapterUrl)) }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANGA_ID = "manga_id"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_PAGES = "pages"
        const val KEY_REFERER = "referer"
        const val KEY_TARGET_DIR = "target_dir"
        private const val NOTIF_ID_PROGRESS = 1001
        private const val NOTIF_ID_COMPLETE = 2000
        private const val NOTIF_ID_FAIL = 3000
        private const val PARALLEL_DOWNLOADS = 4
    }
}
