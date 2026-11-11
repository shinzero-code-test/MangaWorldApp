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
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.util.suspendRunCatching
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: DownloadedMangaDao,
    private val downloadQueueManager: DownloadQueueManager,
    // U-33: the page loop lives in the engine so it is testable off-device.
    private val downloadEngine: ChapterDownloadEngine,
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
        // Single-owner claim: a duplicate worker for the same task (batch chain
        // racing a manual retry chain) must exit quietly instead of downloading
        // into the same directory and deleting the winner's marker below.
        if (
            downloadTaskDao.claimRunningIfQueued(
                taskId,
                pages.size,
                System.currentTimeMillis()
            ) == 0
        ) return@withContext Result.success()
        suspendRunCatching { setForeground(buildForegroundInfo(displayTitle, 0, pages.size, mangaId, chapterUrl)) }

        return@withContext try {
            // U-33: the page loop (chunked parallel download, .part+rename,
            // marker idempotency) lives in ChapterDownloadEngine so it can be
            // verified on the JVM. The worker keeps the Room bookkeeping.
            // A single dead page (durable 404/expired URL) makes the engine
            // throw AFTER all other pages had their chance — that is what
            // drives the existing retry path.
            downloadEngine.downloadChapterPages(
                pages = pages,
                referer = referer,
                targetDir = targetDir,
                onProgress = { done, total ->
                    updateProgress(taskId, displayTitle, done, total, mangaId, chapterUrl)
                }
            )
            val completionMarker = File(targetDir, ChapterDownloadEngine.COMPLETION_MARKER)
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
                // Never delete a winner's marker: if the task already reads
                // `completed` (a concurrent worker won the race), the marker on
                // disk belongs to that success. Only clean up when the task did
                // NOT complete (cancelled/paused/failed meanwhile).
                if (downloadTaskDao.getById(taskId)?.status != "completed") {
                    completionMarker.delete()
                }
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
                reason = userFacingError
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
            .notify(stableNotifId(NOTIF_ID_COMPLETE, mangaId + chapterUrl), notif)
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
            .notify(stableNotifId(NOTIF_ID_FAIL, mangaId + chapterUrl), notif)
    }

    /**
     * Stable per-task IDs inside disjoint bands ([20000..28999] complete,
     * [30000..38999] fail). The old currentTimeMillis().toInt() % 10000 could
     * go negative and cross bands, letting failures replace completions.
     */
    internal fun stableNotifId(base: Int, key: String): Int =
        DownloadNotifIds.stableId(base, key)

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
        suspendRunCatching { setForeground(buildForegroundInfo(title, done, total, mangaId, chapterUrl)) }
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
        internal const val NOTIF_ID_PROGRESS = 1001
        internal const val NOTIF_ID_COMPLETE = 20000
        internal const val NOTIF_ID_FAIL = 30000
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private val ACTIVE_TASK_STATUSES = setOf("queued", "running")
    }
}

/**
 * Pure-JVM notification-ID contract (#1): per-task IDs live in disjoint bands
 * so a failure can never replace a completion. Single source of truth for the
 * worker, the queue manager, and the unit test — no Android dependencies.
 */
internal object DownloadNotifIds {
    const val PROGRESS_BASE = 1001
    const val PROGRESS_SPAN = 9000 // [1001..10000]
    const val COMPLETE_BASE = 20000
    const val FAIL_BASE = 30000
    const val TASK_SPAN = 9000 // [x0000..x8999]
    const val BATCH_BASE = 40000
    const val BATCH_SPAN = 1000 // [40000..40999]

    fun stableId(base: Int, key: String): Int =
        base + ((key.hashCode() and Int.MAX_VALUE) % 9000)

    fun batchId(key: String): Int =
        BATCH_BASE + ((key.hashCode() and Int.MAX_VALUE) % BATCH_SPAN)
}
