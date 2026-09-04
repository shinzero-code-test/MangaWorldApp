package com.exapps.mangaworld.core.data.download

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.room.withTransaction
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.work.await
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadBatchDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadBatchEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A chapter whose pages were resolved before creating a durable download work item. */
data class PreparedChapterDownload(
    val chapterUrl: String,
    val chapterTitle: String?,
    val pages: List<ChapterPage>,
    val referer: String
)

/** A page-resolution failure that remains visible and manually retryable in Downloads. */
data class FailedChapterDownload(
    val chapterUrl: String,
    val chapterTitle: String?,
    val errorMessage: String
)

@Singleton
class DownloadQueueManager @Inject constructor(
    private val app: Application,
    private val database: MangaDatabase,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadBatchDao: DownloadBatchDao,
    private val downloadedMangaDao: DownloadedMangaDao,
    private val okHttpClient: OkHttpClient,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val mangaRepository: MangaRepository
) {
    private val queueMutex = Mutex()

    // ─── Observe ──────────────────────────────────────────────────────────────

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()
    fun observeTask(taskId: String): Flow<DownloadTaskEntity?> = downloadTaskDao.observeById(taskId)
    fun observeDownloadedMangas(): Flow<List<DownloadedMangaEntity>> = downloadedMangaDao.observeAll()

    suspend fun pendingTaskId(mangaId: String, chapterUrl: String): String? =
        downloadTaskDao.getPendingByChapter(chapterUrl, mangaId)?.id

    // ─── Chapter key (trim trailing slash so WordPress URLs work) ─────────────

    private val downloadsRoot: File
        get() = File(app.getExternalFilesDir(null), "downloads")

    private fun mangaDir(mangaId: String, title: String? = null): File =
        DownloadStorage.resolveExistingMangaDir(downloadsRoot, mangaId, title)

    private fun canonicalMangaDir(mangaId: String): File =
        DownloadStorage.canonicalMangaDir(downloadsRoot, mangaId)

    private fun chapterDir(mangaId: String, chapterUrl: String, title: String? = null): File =
        DownloadStorage.resolveExistingChapterDir(downloadsRoot, mangaId, chapterUrl, title)

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun isChapterDownloaded(mangaId: String, chapterUrl: String, title: String? = null): Boolean {
        val dir = chapterDir(mangaId, chapterUrl, title)
        if (!dir.exists() || !File(dir, ".completed").exists()) return false
        return dir.listFiles()?.any { it.isFile && it.extension.lowercase() in setOf("jpg", "png", "webp") } == true
    }

    fun getLocalChapterPages(mangaId: String, chapterUrl: String, title: String? = null): List<ChapterPage> {
        val dir = chapterDir(mangaId, chapterUrl, title)
        if (!dir.exists() || !File(dir, ".completed").exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "png", "webp") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapIndexed { index, file -> ChapterPage(index = index, url = file.toURI().toString()) }
            ?: emptyList()
    }

    /** Count locally-downloaded chapters for a manga by scanning the directory. */
    fun countDownloadedChapters(mangaId: String, title: String? = null): Int {
        // Use title for dir path so it matches the download path
        val dir = if (title != null) mangaDir(mangaId, title) else mangaDir(mangaId, null)
        if (!dir.exists()) return 0
        return dir.listFiles()?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0
    }

    // ─── Enqueue ──────────────────────────────────────────────────────────────

    suspend fun enqueueAndRun(
        taskId: String,
        mangaId: String,
        mangaTitle: String,
        chapterUrl: String,
        chapterTitle: String?,
        pages: List<ChapterPage>,
        wifiOnly: Boolean = true,
        referer: String = "",
        mangaMetadata: DownloadedMangaEntity? = null,
        sourceId: String = mangaId.substringBefore('_'),
        mangaSlug: String = ""
    ): Boolean {
        // Check-then-insert must be atomic or two concurrent enqueues for the same chapter can
        // both pass the dedup check and create duplicate rows/workers.
        val task = queueMutex.withLock {
            if (
                pages.isEmpty() ||
                isChapterDownloaded(mangaId, chapterUrl, mangaTitle) ||
                downloadTaskDao.getPendingByChapter(chapterUrl, mangaId) != null
            ) return false

            DownloadStorage.migrateLegacyDirectoryIfNeeded(downloadsRoot, mangaId, mangaTitle)
            val targetDir = DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
            DownloadTaskEntity(
                id = taskId,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterUrl = chapterUrl,
                chapterTitle = chapterTitle,
                sourceId = sourceId,
                mangaSlug = mangaSlug,
                wifiOnly = wifiOnly,
                targetDir = targetDir.absolutePath,
                referer = referer,
                pagesJson = JSONArray(pages.map { it.url }).toString(),
                status = "queued"
            ).also { downloadTaskDao.upsert(it) }
        }
        analyticsManager.logDownloadStatus(
            mangaId = mangaId,
            sourceId = mangaId.substringBefore('_'),
            status = "queued",
            totalPages = pages.size
        )

        persistMangaMetadata(mangaId, mangaMetadata)
        enqueueWorker(task)
        return true
    }

    /**
     * Persist all work before enqueueing it. A unique per-manga work chain then processes one
     * chapter at a time, which prevents a whole-manga request from overwhelming the source or
     * WorkManager and enables a single completion notification.
     */
    suspend fun enqueueBatch(
        mangaId: String,
        mangaTitle: String,
        mangaSlug: String,
        sourceId: String,
        ready: List<PreparedChapterDownload>,
        failed: List<FailedChapterDownload>,
        wifiOnly: Boolean,
        mangaMetadata: DownloadedMangaEntity? = null
    ): Int {
        // A whole-manga selection should never create two durable rows for the same chapter,
        // even when a scraper returns duplicate chapter URLs.
        // Eager List filters — Sequence lambdas defer suspension past the coroutine body.
        val accepted = ready
            .filter { it.pages.isNotEmpty() }
            .distinctBy { it.chapterUrl }
            .filter { request ->
                !isChapterDownloaded(mangaId, request.chapterUrl) &&
                    downloadTaskDao.getPendingByChapter(request.chapterUrl, mangaId) == null
            }
        val acceptedUrls = accepted.mapTo(mutableSetOf()) { it.chapterUrl }
        val failures = failed
            .filterNot { it.chapterUrl in acceptedUrls }
            .distinctBy { it.chapterUrl }
            .filter { failure ->
                !isChapterDownloaded(mangaId, failure.chapterUrl) &&
                    downloadTaskDao.getPendingByChapter(failure.chapterUrl, mangaId) == null
            }
        val total = accepted.size + failures.size
        if (total == 0) return 0

        DownloadStorage.migrateLegacyDirectoryIfNeeded(downloadsRoot, mangaId, mangaTitle)
        val batchId = "batch_${UUID.randomUUID()}"
        val batch = DownloadBatchEntity(
            id = batchId,
            mangaId = mangaId,
            mangaTitle = mangaTitle,
            totalChapters = total,
            failedChapters = failures.size
        )
        val failedTasks = failures.map { failure ->
            val targetDir = DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, failure.chapterUrl)
            DownloadTaskEntity(
                id = "dl_${UUID.randomUUID()}",
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterUrl = failure.chapterUrl,
                chapterTitle = failure.chapterTitle,
                sourceId = sourceId,
                mangaSlug = mangaSlug,
                batchId = batchId,
                wifiOnly = wifiOnly,
                targetDir = targetDir.absolutePath,
                status = "failed",
                failureNotified = true,
                errorMessage = failure.errorMessage
            )
        }
        val queued = accepted.map { request ->
            DownloadTaskEntity(
                id = "dl_${UUID.randomUUID()}",
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterUrl = request.chapterUrl,
                chapterTitle = request.chapterTitle,
                sourceId = sourceId,
                mangaSlug = mangaSlug,
                batchId = batchId,
                wifiOnly = wifiOnly,
                targetDir = DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, request.chapterUrl).absolutePath,
                referer = request.referer,
                pagesJson = JSONArray(request.pages.map { it.url }).toString(),
                status = "queued"
            )
        }
        database.withTransaction {
            downloadBatchDao.upsert(batch)
            downloadTaskDao.upsertAll(failedTasks + queued)
        }
        persistMangaMetadata(mangaId, mangaMetadata)
        if (queued.isEmpty()) {
            notifyBatchIfTerminal(batchId)
        } else {
            enqueueBatchWorkers(queued)
        }
        return total
    }

    private suspend fun persistMangaMetadata(mangaId: String, mangaMetadata: DownloadedMangaEntity?) {
        mangaMetadata ?: return
        val existing = downloadedMangaDao.get(mangaId)
        downloadedMangaDao.upsert(
            mangaMetadata.copy(
                downloadedChapters = existing?.downloadedChapters ?: 0,
                downloadedAt = existing?.downloadedAt ?: System.currentTimeMillis()
            )
        )
        saveCoverAndMetadata(canonicalMangaDir(mangaId), mangaMetadata)
    }

    private fun enqueueWorker(task: DownloadTaskEntity) {
        val request = buildWorkerRequest(task)
        WorkManager.getInstance(app).enqueueUniqueWork(
            mangaQueueName(task.mangaId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /** Adds a fully prepared manga batch as one ordered WorkManager continuation. */
    private fun enqueueBatchWorkers(tasks: List<DownloadTaskEntity>, queueName: String? = null) {
        val requests = tasks.map(::buildWorkerRequest)
        if (requests.isEmpty()) return

        var continuation = WorkManager.getInstance(app).beginUniqueWork(
            queueName ?: mangaQueueName(tasks.first().mangaId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            requests.first()
        )
        requests.drop(1).forEach { request ->
            continuation = continuation.then(request)
        }
        continuation.enqueue()
    }

    /**
     * Manual single-chapter retries bypass the per-manga batch chain entirely.
     * The batch chain (up to 75 chapters under manga_download_queue_<id>) stays
     * pending while the user taps retry — APPEND_OR_REPLACE would sequence the
     * retry BEHIND chapters 26–75, reading as "retry does nothing" for the whole
     * batch duration. A per-task unique name runs immediately, in parallel.
     */
    private fun enqueueSingleRetry(task: DownloadTaskEntity) {
        WorkManager.getInstance(app).enqueueUniqueWork(
            retryQueueName(task.id),
            ExistingWorkPolicy.REPLACE,
            buildWorkerRequest(task)
        )
    }

    private fun retryQueueName(taskId: String): String = "manga_download_retry_$taskId"

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
                java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag(task.id)
            .addTag("manga_${task.mangaId}")
            .build()
    }

    private fun mangaQueueName(mangaId: String): String = "manga_download_queue_$mangaId"

    /** Reconciles persisted task states and emits exactly one notification per batch. */
    suspend fun reconcileBatchCompletion(batchId: String) {
        downloadBatchDao.synchronizeOutcomeCounts(batchId)
        notifyBatchIfTerminal(batchId)
    }

    private suspend fun notifyBatchIfTerminal(batchId: String) {
        if (downloadBatchDao.claimTerminalNotification(batchId) == 0) return
        // Read after atomically claiming the notification so concurrent workers cannot publish
        // an earlier, non-terminal count in the final batch summary.
        val batch = downloadBatchDao.getById(batchId) ?: return

        val hasFailures = batch.failedChapters > 0
        val notification = NotificationCompat.Builder(app, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(com.exapps.mangaworld.R.drawable.ic_shortcut_downloads)
            .setContentTitle(
                app.getString(
                    if (hasFailures) com.exapps.mangaworld.R.string.download_notification_batch_partial_title
                    else com.exapps.mangaworld.R.string.download_notification_batch_complete_title,
                    batch.mangaTitle
                )
            )
            .setContentText(
                if (hasFailures) {
                    app.getString(
                        com.exapps.mangaworld.R.string.download_notification_batch_partial_body,
                        batch.completedChapters,
                        batch.failedChapters
                    )
                } else {
                    app.getString(
                        com.exapps.mangaworld.R.string.download_notification_batch_complete_body,
                        batch.completedChapters
                    )
                }
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    app,
                    (batch.id + "complete").hashCode(),
                    AppLaunchIntents.downloads(app),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_BATCH + (batch.id.hashCode() and 0x0FFF), notification)
    }

    // ─── Cancel / delete ─────────────────────────────────────────────────────

    suspend fun cancelTask(taskId: String) = queueMutex.withLock {
        val task = downloadTaskDao.getById(taskId) ?: return@withLock
        if (task.status == "completed") return@withLock
        val now = System.currentTimeMillis()
        val incomplete = downloadTaskDao.getIncompleteByMangaId(task.mangaId)
        val targetIsIncomplete = incomplete.any { it.id == taskId }
        if (targetIsIncomplete) {
            incomplete.forEach { queuedTask ->
                val updatedTask = when {
                    queuedTask.id == taskId -> queuedTask.copy(
                        status = "cancelled",
                        updatedAt = now,
                        errorMessage = ChapterDownloadWorker.ERROR_CANCELLED
                    )
                    queuedTask.status == "running" -> queuedTask.copy(
                        status = "queued",
                        updatedAt = now
                    )
                    else -> queuedTask
                }
                if (updatedTask != queuedTask) downloadTaskDao.upsert(updatedTask)
            }
            cancelMangaQueue(task.mangaId)
            cancelRetryQueue(taskId)
            enqueueQueuedTasks(task.mangaId)
        } else {
            downloadTaskDao.upsert(
                task.copy(
                    status = "cancelled",
                    updatedAt = now,
                    errorMessage = ChapterDownloadWorker.ERROR_CANCELLED
                )
            )
        }
        analyticsManager.logDownloadStatus(
            mangaId = task.mangaId,
            sourceId = task.mangaId.substringBefore('_'),
            status = "cancelled",
            totalPages = runCatching { JSONArray(task.pagesJson).length() }.getOrDefault(0)
        )
        withContext(Dispatchers.IO) {
            deleteChapterDirectory(task.mangaId, task.chapterUrl, task.mangaTitle)
            refreshDownloadedCount(task.mangaId)
        }
        val batchId = task.batchId
        if (batchId != null) reconcileBatchCompletion(batchId)
    }

    suspend fun pauseTask(taskId: String) = queueMutex.withLock {
        val task = downloadTaskDao.getById(taskId) ?: return@withLock
        if (task.status !in setOf("running", "queued")) return@withLock
        pauseMangaTasks(task.mangaId, setOf(taskId))
    }

    suspend fun resumeTask(taskId: String) = queueMutex.withLock {
        val task = downloadTaskDao.getById(taskId) ?: return@withLock
        if (task.status != "paused") return@withLock
        resubmitTasks(listOf(task), isManualSingleRetry = true)
    }

    suspend fun pauseAll() = queueMutex.withLock {
        downloadTaskDao.getAllIncomplete()
            .filter { it.status == "queued" || it.status == "running" }
            .groupBy(DownloadTaskEntity::mangaId)
            .forEach { (mangaId, tasks) ->
                pauseMangaTasks(mangaId, tasks.mapTo(mutableSetOf(), DownloadTaskEntity::id))
            }
    }

    suspend fun resumeAll() = queueMutex.withLock {
        downloadTaskDao.getAllPaused()
            .groupBy(DownloadTaskEntity::mangaId)
            .forEach { (_, tasks) ->
                resubmitTasks(tasks)
            }
    }

    suspend fun cancelAllDownloads() = queueMutex.withLock {
        downloadTaskDao.getAllIncomplete()
            .groupBy(DownloadTaskEntity::mangaId)
            .forEach { (mangaId, tasks) ->
                cancelMangaDownloadsLocked(mangaId, tasks)
            }
    }

    suspend fun cancelMangaDownloads(mangaId: String) = queueMutex.withLock {
        val tasks = downloadTaskDao.getIncompleteByMangaId(mangaId)
        if (tasks.isNotEmpty()) {
            cancelMangaDownloadsLocked(mangaId, tasks)
        }
    }

    suspend fun retryTask(taskId: String) = queueMutex.withLock {
        val task = downloadTaskDao.getById(taskId) ?: return@withLock
        // v8 (#10): a MANUAL retry must not keep re-downloading the same stale
        // cached page list — that is exactly the loop where failed chapters
        // failed forever. Force fresh resolution from the source.
        resubmitTasks(listOf(task), forceFreshPages = true, isManualSingleRetry = true)
    }

    private suspend fun pauseMangaTasks(mangaId: String, pausedTaskIds: Set<String>) {
        val now = System.currentTimeMillis()
        downloadTaskDao.getIncompleteByMangaId(mangaId).forEach { task ->
            val updatedTask = when {
                task.id in pausedTaskIds -> task.copy(status = "paused", updatedAt = now)
                task.status == "running" -> task.copy(status = "queued", updatedAt = now)
                else -> task
            }
            if (updatedTask != task) downloadTaskDao.upsert(updatedTask)
        }
        cancelMangaQueue(mangaId)
        // A retry chain runs outside the batch queue — pause must stop it too,
        // otherwise the "paused" chapter keeps downloading in the background.
        pausedTaskIds.forEach { cancelRetryQueue(it) }
        enqueueQueuedTasks(mangaId)
    }

    private suspend fun cancelMangaDownloadsLocked(
        mangaId: String,
        tasks: List<DownloadTaskEntity>
    ) {
        val now = System.currentTimeMillis()
        tasks.forEach { task ->
            downloadTaskDao.upsert(
                task.copy(
                    status = "cancelled",
                    updatedAt = now,
                    errorMessage = ChapterDownloadWorker.ERROR_CANCELLED
                )
            )
        }
        cancelMangaQueue(mangaId)
        tasks.forEach { cancelRetryQueue(it.id) }
        withContext(Dispatchers.IO) {
            tasks.forEach { task ->
                deleteChapterDirectory(task.mangaId, task.chapterUrl, task.mangaTitle)
            }
            // One recount per manga covers every deleted directory above.
            refreshDownloadedCount(mangaId)
        }
        tasks.mapNotNull(DownloadTaskEntity::batchId).distinct().forEach { reconcileBatchCompletion(it) }
    }

    private suspend fun resubmitTasks(
        tasks: List<DownloadTaskEntity>,
        forceFreshPages: Boolean = false,
        isManualSingleRetry: Boolean = false
    ) {
        val queuedTasks = mutableListOf<DownloadTaskEntity>()
        val failedBatchIds = mutableSetOf<String>()
        tasks.forEach { task ->
            val pages = resolvePagesForRetry(task, ignoreCache = forceFreshPages)
            if (pages.isEmpty()) {
                downloadTaskDao.upsert(
                    task.copy(
                        status = "failed",
                        errorMessage = ChapterDownloadWorker.ERROR_RETRY_UNAVAILABLE,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                task.batchId?.let(failedBatchIds::add)
            } else {
                // Fresh resolution may return a different URL list than the files
                // already on disk (index-named). Wipe the partial dir so page-level
                // resume cannot pair new URLs with old images or overcount "done".
                if (forceFreshPages) {
                    withContext(Dispatchers.IO) { deleteChapterDirectory(task.mangaId, task.chapterUrl, task.mangaTitle) }
                }
                val referer = pages.firstOrNull()?.headers?.get("Referer")
                    ?.takeIf { it.isNotBlank() }
                    ?: task.referer.ifBlank { task.chapterUrl }
                val queuedTask = task.copy(
                    referer = referer,
                    pagesJson = JSONArray(pages.map { it.url }).toString(),
                    status = "queued",
                    progress = 0f,
                    retries = 0,
                    errorMessage = null,
                    failureNotified = false,
                    updatedAt = System.currentTimeMillis()
                )
                downloadTaskDao.upsert(queuedTask)
                queuedTasks += queuedTask
            }
        }
        failedBatchIds.forEach { reconcileBatchCompletion(it) }
        if (isManualSingleRetry && queuedTasks.size == 1) {
            enqueueSingleRetry(queuedTasks.first())
        } else {
            enqueueBatchWorkers(queuedTasks)
        }
    }

    private suspend fun cancelMangaQueue(mangaId: String) {
        WorkManager.getInstance(app).cancelUniqueWork(mangaQueueName(mangaId)).await()
    }

    /** Cancels a per-task retry chain started by [enqueueSingleRetry], if any. */
    private suspend fun cancelRetryQueue(taskId: String) {
        WorkManager.getInstance(app).cancelUniqueWork(retryQueueName(taskId)).await()
    }

    private suspend fun enqueueQueuedTasks(mangaId: String) {
        enqueueBatchWorkers(downloadTaskDao.getQueuedByMangaId(mangaId))
    }

    private suspend fun resolvePagesForRetry(task: DownloadTaskEntity, ignoreCache: Boolean = false): List<ChapterPage> {
        // Tolerant parse (mirrors ChapterDownloadWorker.toPageUrls): a stale or
        // partially-written pagesJson must degrade to "skip bad entries", never
        // to an empty list that fails the retry instantly while old files sit
        // on disk looking like a phantom failure.
        val cachedPages = runCatching {
            val array = JSONArray(task.pagesJson)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }?.let { ChapterPage(index, it) }
            }
        }.getOrDefault(emptyList())

        // v8 (#10, round 2): manual retries prefer FRESH resolution so expired or
        // rotated page URLs are not re-downloaded forever — but if the source is
        // unreachable (Cloudflare challenge, network blip, source layout change),
        // falling back to the cached list still beats an instant identical fail.
        suspend fun fetchFresh(): List<ChapterPage> {
            val source = MangaSource.fromIdOrNull(task.sourceId.ifBlank { task.mangaId.substringBefore('_') })
                ?: return emptyList()
            val slug = task.mangaSlug.ifBlank { task.mangaId.substringAfter("${source.id}_") }
            return mangaRepository.getChapterPages(slug, task.chapterUrl, source).getOrDefault(emptyList())
        }

        return if (ignoreCache) {
            fetchFresh().ifEmpty { cachedPages }
        } else {
            cachedPages.ifEmpty { fetchFresh() }
        }
    }

    suspend fun clearCompleted() = downloadTaskDao.clearCompleted()

    /**
     * Re-enqueue durable task rows that were stranded `queued` by a crash between the Room
     * write and the WorkManager enqueue (or a lost work chain). `running` rows whose worker
     * is gone are reset to `queued` first, mirroring pauseMangaTasks.
     */
    suspend fun recoverOrphanTasks() = queueMutex.withLock {
        val orphans = downloadTaskDao.getAllIncomplete()
            .filter { it.status == "queued" || it.status == "running" }
            .groupBy(DownloadTaskEntity::mangaId)
        val now = System.currentTimeMillis()
        orphans.forEach { (mangaId, tasks) ->
            // A `running` row may belong to a worker killed with the process — reset it so the
            // fresh chain picks it up. If a worker was somehow still alive, APPEND_OR_REPLACE
            // supersedes its stale chain and page-level resume makes the redo cheap.
            tasks.filter { it.status == "running" }.forEach { runningTask ->
                downloadTaskDao.upsert(runningTask.copy(status = "queued", updatedAt = now))
            }
            enqueueBatchWorkers(downloadTaskDao.getQueuedByMangaId(mangaId))
        }
    }

    /** Removes finished task rows from the Downloads list without touching downloaded files. */
    suspend fun dismissTasks(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        downloadTaskDao.deleteByIds(taskIds)
    }

    suspend fun getDownloadedChapterDir(mangaId: String, chapterUrl: String): String? =
        DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
            .takeIf { it.exists() && File(it, ".completed").exists() }
            ?.absolutePath

    /**
     * Delete ALL downloaded content for a manga: files on disk, task records,
     * and the downloaded_manga metadata row.
     */
    suspend fun deleteDownloadedManga(mangaId: String) = queueMutex.withLock {
        // Cancel any active work — batch chain plus any per-task retry chains,
        // which live under their own unique names outside the batch queue.
        WorkManager.getInstance(app).cancelUniqueWork(mangaQueueName(mangaId)).await()
        WorkManager.getInstance(app).cancelAllWorkByTag("manga_$mangaId").await()
        // Delete files
        deleteMangaDirectory(canonicalMangaDir(mangaId))
        downloadedMangaDao.get(mangaId)?.title?.let { title ->
            DownloadStorage.legacyMangaDir(downloadsRoot, title)?.let(::deleteMangaDirectory)
        }
        // Remove DB records
        downloadTaskDao.deleteByMangaId(mangaId)
        downloadBatchDao.deleteByMangaId(mangaId)
        downloadedMangaDao.delete(mangaId)
    }

    suspend fun deleteDownloadedChapterDir(mangaId: String, chapterUrl: String) {
        deleteChapterDirectory(mangaId, chapterUrl)
        refreshDownloadedCount(mangaId)
    }

    private fun deleteChapterDirectory(mangaId: String, chapterUrl: String, title: String? = null) {
        val directory = chapterDir(mangaId, chapterUrl, title)
        if (DownloadStorage.isChapterDirectory(downloadsRoot, mangaId, directory)) {
            directory.deleteRecursively()
        }
    }

    private fun deleteMangaDirectory(directory: File) {
        if (DownloadStorage.isMangaDirectory(downloadsRoot, directory)) {
            directory.deleteRecursively()
        }
    }

    /** Update the chapter count in the downloaded_manga table after a chapter completes. */
    suspend fun refreshDownloadedCount(mangaId: String) {
        val count = countDownloadedChapters(mangaId)
        downloadedMangaDao.updateChapterCount(mangaId, count)
    }
    // ─── Offline metadata ─────────────────────────────────────────────────────

    /** Downloads the cover and writes metadata.json into the manga folder. */
    suspend fun saveCoverAndMetadata(
        dir: File, metadata: DownloadedMangaEntity
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        // Cover
        val coverFile = File(dir, "cover.jpg")
        if (!coverFile.exists() && metadata.coverUrl.isNotBlank()) {
            runCatching {
                val req = Request.Builder().url(metadata.coverUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    resp.body?.byteStream()?.use { inp ->
                        coverFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                }
                // Targeted update: re-upserting a stale metadata copy here could revert
                // downloadedChapters if a chapter completed while the cover was downloading.
                downloadedMangaDao.updateCoverPath(metadata.mangaId, coverFile.absolutePath)
            }
        }
        // metadata.json
        writeMetadataJson(dir, metadata)
    }

    private fun writeMetadataJson(dir: File, metadata: DownloadedMangaEntity) {
        runCatching {
            val genresArr = runCatching { org.json.JSONArray(metadata.genresJson) }.getOrDefault(JSONArray())
            val obj = JSONObject().apply {
                put("mangaId", metadata.mangaId)
                put("slug", metadata.slug)
                put("title", metadata.title)
                put("source", metadata.sourceId)
                put("coverUrl", metadata.coverUrl)
                put("localCoverPath", metadata.localCoverPath ?: "")
                put("status", metadata.statusStr)
                put("type", metadata.typeStr)
                put("genres", genresArr)
                put("description", metadata.description)
                put("totalChapters", metadata.totalChapters)
                put("downloadedAt", metadata.downloadedAt)
                // List downloaded chapter dirs
                val chapters = JSONArray()
                dir.listFiles()
                    ?.filter { it.isDirectory && File(it, ".completed").exists() }
                    ?.sortedBy { it.name }
                    ?.forEach { chDir -> chapters.put(chDir.name) }
                put("downloadedChapters", chapters)
            }
            File(dir, "metadata.json").writeText(obj.toString(2))
        }
    }

    fun getMangaDirPath(mangaId: String, title: String? = null): String =
        mangaDir(mangaId, title).absolutePath

    // ─── DB helpers ────────────────────────────────────────────────────────

    suspend fun upsertDownloadedManga(entity: DownloadedMangaEntity) {
        downloadedMangaDao.upsert(entity)
    }

    /**
     * Scan the downloads directory for manga folders that have a metadata.json
     * but no corresponding row in the downloaded_manga table, and insert them.
     * This is a safety net for: imported manga, crash recovery, and DB migrations.
     */
    suspend fun syncFileSystemWithDatabase() = withContext(Dispatchers.IO) {
        if (!downloadsRoot.exists()) return@withContext
        val existingIds = downloadedMangaDao.getAll().map { it.mangaId }.toMutableSet()

        downloadsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metadataFile = File(dir, "metadata.json")
            if (!metadataFile.exists()) return@forEach
            try {
                val json = org.json.JSONObject(metadataFile.readText())
                // Support both imported format ("id") and downloaded format ("mangaId")
                val mangaId = json.optString("mangaId", "")
                    .ifBlank { json.optString("id", dir.name) }
                if (mangaId.isBlank() || mangaId in existingIds) return@forEach

                // Count completed chapter dirs
                val completedCount = dir.listFiles()
                    ?.count { it.isDirectory && File(it, ".completed").exists() } ?: 0

                val coverFile = File(dir, "cover.jpg")
                val entity = DownloadedMangaEntity(
                    mangaId = mangaId,
                    slug = json.optString("slug", mangaId),
                    title = json.optString("title", dir.name),
                    coverUrl = json.optString("coverUrl", ""),
                    localCoverPath = coverFile.absolutePath.takeIf { coverFile.exists() },
                    sourceId = json.optString("source",
                        json.optString("sourceId", "unknown")),
                    totalChapters = json.optInt("totalChapters", completedCount)
                        .coerceAtLeast(completedCount),
                    downloadedChapters = completedCount,
                    genresJson = json.optJSONArray("genres")?.toString() ?: "[]",
                    statusStr = json.optString("status", "UNKNOWN"),
                    typeStr = json.optString("type", "MANGA"),
                    description = json.optString("description", ""),
                    downloadedAt = json.optLong("downloadedAt",
                        json.optLong("importedAt", System.currentTimeMillis()))
                )
                downloadedMangaDao.upsert(entity)
                existingIds.add(mangaId) // avoid duplicates in same scan
            } catch (_: Exception) { /* skip malformed metadata */ }
        } // forEach dir
    }

    private companion object {
        const val MIN_RETRY_BACKOFF_SECONDS = 10L

        /** Batch-summary notifications live above every per-task range (see ChapterDownloadWorker). */
        const val NOTIF_ID_BATCH = 40000
    }
}