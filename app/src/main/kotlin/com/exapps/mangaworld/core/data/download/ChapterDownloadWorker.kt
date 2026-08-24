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
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import org.json.JSONArray

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: DownloadedMangaDao,
    private val downloadQueueManager: DownloadQueueManager,
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val analyticsManager: FirebaseAnalyticsManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val task = downloadTaskDao.getById(taskId) ?: return@withContext Result.failure()
        if (task.status !in ACTIVE_TASK_STATUSES) return@withContext Result.success()
        val mangaId = task.mangaId
        val chapterUrl = task.chapterUrl
        val chapterTitle = task.chapterTitle
        val referer = task.referer
        val batchId = task.batchId
        // Keep WorkManager input small (it has a strict size cap); durable URLs live in Room.
        val pages = task.pagesJson.toPageUrls()
        if (pages.isEmpty()) {
            val message = ERROR_RETRY_UNAVAILABLE
            if (
                downloadTaskDao.updateFailureStateIfActive(
                    id = taskId,
                    status = "failed",
                    retries = task.retries,
                    errorMessage = message,
                    updatedAt = System.currentTimeMillis()
                ) == 0
            ) return@withContext Result.success()
            if (batchId == null) {
                if (downloadTaskDao.markFailureNotified(taskId) > 0) {
                    showFailureNotification(chapterTitle ?: chapterUrl, mangaId, chapterUrl)
                }
            } else {
                downloadQueueManager.reconcileBatchCompletion(batchId)
            }
            return@withContext Result.success()
        }

        val downloadsRoot = File(appContext.getExternalFilesDir(null), "downloads")
        val targetDir = DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
        val mangaDir = DownloadStorage.canonicalMangaDir(downloadsRoot, mangaId)
        if (!targetDir.exists()) targetDir.mkdirs()

        val chapterKey = DownloadStorage.chapterKey(chapterUrl)
        val displayTitle = chapterTitle ?: appContext.getString(R.string.fmt_059, chapterKey)
        if (
            downloadTaskDao.updateStateIfActive(
                taskId,
                "running",
                0f,
                0,
                pages.size,
                System.currentTimeMillis(),
                null
            ) == 0
        ) return@withContext Result.success()
        runCatching { setForeground(buildForegroundInfo(displayTitle, 0, pages.size, mangaId, chapterUrl)) }

        return@withContext try {
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

            val pendingMarker = File(targetDir, ".completed.part")
            val completionMarker = File(targetDir, ".completed")
            pendingMarker.writeText("ok")
            check(pendingMarker.renameTo(completionMarker)) { "completion marker rename failed" }
            if (
                downloadTaskDao.updateStateIfActive(
                    taskId,
                    "completed",
                    1f,
                    pages.size,
                    pages.size,
                    System.currentTimeMillis(),
                    null
                ) == 0
            ) {
                completionMarker.delete()
                return@withContext Result.success()
            }
            analyticsManager.logDownloadStatus(
                mangaId = mangaId,
                sourceId = mangaId.substringBefore('_'),
                status = "completed",
                totalPages = pages.size,
                retryCount = runAttemptCount
            )

            val count = mangaDir.listFiles()?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0
            downloadedMangaDao.updateChapterCount(mangaId, count)

            if (batchId == null) {
                showCompletionNotification(displayTitle, pages.size, mangaId, chapterUrl)
            } else {
                downloadQueueManager.reconcileBatchCompletion(batchId)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val current = downloadTaskDao.getById(taskId)
            val retries = (current?.retries ?: 0) + 1
            val userFacingError = ERROR_DOWNLOAD_FAILED
            if (runAttemptCount + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                val wasRescheduled = downloadTaskDao.updateFailureStateIfActive(
                    id = taskId,
                    status = "queued",
                    retries = retries,
                    errorMessage = userFacingError,
                    updatedAt = System.currentTimeMillis()
                ) > 0
                return@withContext if (wasRescheduled) Result.retry() else Result.success()
            }

            val failed = downloadTaskDao.updateFailureStateIfActive(
                id = taskId,
                status = "failed",
                retries = retries,
                errorMessage = userFacingError,
                updatedAt = System.currentTimeMillis()
            ) > 0
            if (!failed) return@withContext Result.success()
            analyticsManager.logDownloadStatus(
                mangaId = mangaId,
                sourceId = mangaId.substringBefore('_'),
                status = "failed",
                totalPages = pages.size,
                retryCount = retries,
                reason = e.message
            )
            if (batchId == null) {
                // A terminal failure is shown once only. Manual retry explicitly resets this
                // flag; returning success prevents WorkManager from re-running it on launch.
                if (downloadTaskDao.markFailureNotified(taskId) > 0) {
                    showFailureNotification(displayTitle, mangaId, chapterUrl)
                }
            } else {
                downloadQueueManager.reconcileBatchCompletion(batchId)
            }
            Result.success()
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
        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $pageUrl")
            val body = response.body ?: error("Empty body for $pageUrl")
            body.byteStream().use { input ->
                tempFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
        if (tempFile.length() <= 0L) {
            tempFile.delete()
            error("Downloaded empty image for $pageUrl")
        }
        check(tempFile.renameTo(outFile)) { appContext.getString(R.string.download_error) }
        return true
    }

    private fun buildForegroundInfo(title: String, done: Int, total: Int, mangaId: String, chapterUrl: String): ForegroundInfo {
        val sourceId = mangaId.substringBefore('_')
        // All chapters in a manga reuse one silent foreground notification rather than leaving
        // a new persistent notification for every chapter in a whole-manga download.
        val chapterNotifId = NOTIF_ID_PROGRESS + Math.abs(mangaId.hashCode() % 9000)
        val notification = NotificationCompat.Builder(appContext, MangaWorldApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_downloads)
            .setContentTitle(appContext.getString(R.string.download_notification_progress_title, title))
            .setContentText(
                if (total > 0) appContext.getString(R.string.download_notification_progress_pages, done, total)
                else appContext.getString(R.string.download_notification_progress_indeterminate)
            )
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
            ForegroundInfo(chapterNotifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(chapterNotifId, notification)
        }
    }

    private fun showCompletionNotification(title: String, pages: Int, mangaId: String, chapterUrl: String) {
        val sourceId = mangaId.substringBefore('_')
        val notif = NotificationCompat.Builder(appContext, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_downloads)
            .setContentTitle(appContext.getString(R.string.download_notification_complete_title, title))
            .setContentText(appContext.getString(R.string.download_notification_complete_body, pages))
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
            .setSmallIcon(R.drawable.ic_shortcut_downloads)
            .setContentTitle(appContext.getString(R.string.download_notification_failed_title, title))
            .setContentText(appContext.getString(R.string.download_notification_failed_body))
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
        downloadTaskDao.updateStateIfActive(
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

    private fun String.toPageUrls(): List<String> = runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val KEY_TASK_ID = "task_id"

        /** Stable, language-neutral error codes persisted in Room and translated at render time. */
        const val ERROR_RETRY_UNAVAILABLE = "retry_unavailable"
        const val ERROR_DOWNLOAD_FAILED = "download_error"
        const val ERROR_CANCELLED = "cancelled"

        // Notification ID ranges are disjoint: progress [1001..10_000],
        // completion [20_000..29_999], failure [30_000..39_999], batch [40_000..40_999].
        private const val NOTIF_ID_PROGRESS = 1001
        private const val NOTIF_ID_COMPLETE = 20000
        private const val NOTIF_ID_FAIL = 30000
        private const val PARALLEL_DOWNLOADS = 4
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private val ACTIVE_TASK_STATUSES = setOf("queued", "running")
    }
}
