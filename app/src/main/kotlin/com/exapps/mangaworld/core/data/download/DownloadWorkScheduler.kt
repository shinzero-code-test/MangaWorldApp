package com.exapps.mangaworld.core.data.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-33 seam: every WorkManager touch the download queue used to make inline
 * now goes through this interface.
 *
 * Why it exists: `WorkManager.getInstance(...)` is a static that cannot run on
 * the JVM (android.jar is stubs-only), so the manager's enqueue/cancel state
 * machine was untestable — six U-33 cases were pinned to a "documented seam
 * limit" instead of being executed. With the seam the REAL manager runs
 * against a recording fake, and the fake asserts the work WorkManager would
 * have received: unique names, ordering, batch continuation and cancel scope.
 *
 * Contract notes (kept identical to the pre-seam implementation):
 * - The per-manga chain name and the per-task retry name are PERSISTED in
 *   WorkManager's database across app versions. Renaming one would orphan
 *   already-enqueued work, so the strings are fixed forever.
 * - The tag `manga_<id>` is what `cancelAllForManga` matches on.
 */
interface DownloadWorkScheduler {
    /** Appends one chapter download to the per-manga work chain. */
    fun enqueueChapter(task: DownloadTaskEntity)

    /**
     * Appends an ordered batch to the per-manga work chain. The chain runs one
     * chapter at a time so a whole-manga request cannot overwhelm the source.
     */
    fun enqueueBatch(tasks: List<DownloadTaskEntity>)

    /**
     * Runs one retry OUTSIDE the batch chain (own unique name, runs
     * immediately). APPEND_OR_REPLACE would sequence the retry behind
     * chapters 26–75, reading as "retry does nothing" for the batch
     * duration — so a manual retry gets its own queue.
     */
    fun enqueueRetry(task: DownloadTaskEntity)

    /** Cancels the per-manga chain. */
    suspend fun cancelMangaQueue(mangaId: String)

    /** Cancels the per-task retry chain, if any. */
    suspend fun cancelRetryQueue(taskId: String)

    /** Cancels every work item tagged for the manga (chain + retries). */
    suspend fun cancelAllForManga(mangaId: String)
}

/**
 * Production [DownloadWorkScheduler]: the one place that talks to WorkManager.
 * Everything else in the download stack depends on the interface, so the
 * queue's state machine is verifiable on the JVM.
 */
@Singleton
class WorkManagerDownloadWorkScheduler @Inject constructor(
    private val workManager: WorkManager
) : DownloadWorkScheduler {

    override fun enqueueChapter(task: DownloadTaskEntity) {
        workManager.enqueueUniqueWork(
            mangaQueueName(task.mangaId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildWorkerRequest(task)
        )
    }

    override fun enqueueBatch(tasks: List<DownloadTaskEntity>) {
        if (tasks.isEmpty()) return
        val requests = tasks.map(::buildWorkerRequest)
        var continuation = workManager.beginUniqueWork(
            mangaQueueName(tasks.first().mangaId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            requests.first()
        )
        requests.drop(1).forEach { request ->
            continuation = continuation.then(request)
        }
        continuation.enqueue()
    }

    override fun enqueueRetry(task: DownloadTaskEntity) {
        workManager.enqueueUniqueWork(
            retryQueueName(task.id),
            ExistingWorkPolicy.REPLACE,
            buildWorkerRequest(task)
        )
    }

    override suspend fun cancelMangaQueue(mangaId: String) {
        workManager.cancelUniqueWork(mangaQueueName(mangaId)).await()
    }

    override suspend fun cancelRetryQueue(taskId: String) {
        workManager.cancelUniqueWork(retryQueueName(taskId)).await()
    }

    override suspend fun cancelAllForManga(mangaId: String) {
        workManager.cancelAllWorkByTag(mangaTag(mangaId)).await()
    }

    private fun buildWorkerRequest(task: DownloadTaskEntity): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (task.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(ChapterDownloadWorker.KEY_TASK_ID, task.id)
            .build()
        return OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(task.id)
            .addTag(mangaTag(task.mangaId))
            .build()
    }

    private fun mangaQueueName(mangaId: String): String = "manga_download_queue_$mangaId"

    private fun retryQueueName(taskId: String): String = "manga_download_retry_$taskId"

    private fun mangaTag(mangaId: String): String = "manga_$mangaId"

    private companion object {
        /** WorkManager's exponential backoff floor for the download workers. */
        const val MIN_RETRY_BACKOFF_SECONDS = 10L
    }
}
