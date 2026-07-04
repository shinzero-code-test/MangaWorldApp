package com.exapps.mangaworld.core.data.download

import android.app.Application
import androidx.work.*
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.domain.model.ChapterPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueManager @Inject constructor(
    private val app: Application,
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadedMangaDao: DownloadedMangaDao,
    private val okHttpClient: OkHttpClient,
    private val analyticsManager: FirebaseAnalyticsManager
) {
    // ─── Observe ──────────────────────────────────────────────────────────────

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()
    fun observeDownloadedMangas(): Flow<List<DownloadedMangaEntity>> = downloadedMangaDao.observeAll()

    // ─── Chapter key (trim trailing slash so WordPress URLs work) ─────────────

    private val downloadsRoot: File
        get() = File(app.getExternalFilesDir(null), "downloads")

    /** Safe folder name: strip forbidden chars, limit length. */
    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\\\:*?""<>|]"""), "_").trim().take(80).ifBlank { "manga" }

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
        mangaMetadata: DownloadedMangaEntity? = null
    ) {
        if (downloadTaskDao.getPendingByChapter(chapterUrl, mangaId) != null) return

        DownloadStorage.migrateLegacyDirectoryIfNeeded(downloadsRoot, mangaId, mangaTitle)

        val targetDir = DownloadStorage.canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
        downloadTaskDao.upsert(
            DownloadTaskEntity(
                id = taskId,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterUrl = chapterUrl,
                chapterTitle = chapterTitle,
                targetDir = targetDir.absolutePath,
                referer = referer,
                pagesJson = JSONArray(pages.map { it.url }).toString(),
                status = "queued"
            )
        )
        analyticsManager.logDownloadStatus(
            mangaId = mangaId,
            sourceId = mangaId.substringBefore('_'),
            status = "queued",
            totalPages = pages.size
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
            // Save cover + metadata.json for offline access
            saveCoverAndMetadata(canonicalMangaDir(mangaId), mangaMetadata)
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(ChapterDownloadWorker.KEY_TASK_ID, taskId)
            .putString(ChapterDownloadWorker.KEY_MANGA_ID, mangaId)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, chapterUrl)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, chapterTitle)
            .putString(ChapterDownloadWorker.KEY_REFERER, referer)
            .putString(ChapterDownloadWorker.KEY_TARGET_DIR, targetDir.absolutePath)
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
        analyticsManager.logDownloadStatus(
            mangaId = task.mangaId,
            sourceId = task.mangaId.substringBefore('_'),
            status = "cancelled",
            totalPages = runCatching { JSONArray(task.pagesJson).length() }.getOrDefault(0)
        )
        File(task.targetDir).deleteRecursively()
        WorkManager.getInstance(app).cancelAllWorkByTag(taskId)
    }

    suspend fun pauseTask(taskId: String) {
        val task = downloadTaskDao.getById(taskId) ?: return
        if (task.status != "running") return
        downloadTaskDao.upsert(
            task.copy(status = "paused", updatedAt = System.currentTimeMillis())
        )
        WorkManager.getInstance(app).cancelAllWorkByTag(taskId)
    }

    suspend fun resumeTask(taskId: String) {
        val task = downloadTaskDao.getById(taskId) ?: return
        if (task.status != "paused") return
        downloadTaskDao.upsert(task.copy(status = "queued", updatedAt = System.currentTimeMillis()))
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(app).enqueue(
            OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .setInputData(Data.Builder()
                    .putString(ChapterDownloadWorker.KEY_TASK_ID, task.id)
                    .putString(ChapterDownloadWorker.KEY_MANGA_ID, task.mangaId)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, task.chapterUrl)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, task.chapterTitle)
                    .putString(ChapterDownloadWorker.KEY_REFERER, task.referer)
                    .putString(ChapterDownloadWorker.KEY_TARGET_DIR, task.targetDir)
                    .putStringArray(
                        ChapterDownloadWorker.KEY_PAGES,
                        runCatching {
                            val arr = JSONArray(task.pagesJson)
                            Array(arr.length()) { idx -> arr.getString(idx) }
                        }.getOrDefault(emptyArray())
                    )
                    .build())
                .setConstraints(constraints)
                .addTag(taskId)
                .addTag("manga_${task.mangaId}")
                .build()
        )
    }

    suspend fun pauseAll() {
        downloadTaskDao.observeAll().first().forEach { task ->
            if (task.status == "running" || task.status == "queued") {
                pauseTask(task.id)
            }
        }
    }

    suspend fun resumeAll() {
        downloadTaskDao.observeAll().first().forEach { task ->
            if (task.status == "paused") {
                resumeTask(task.id)
            }
        }
    }

    suspend fun retryTask(taskId: String) {
        val task = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.upsert(task.copy(status = "queued", errorMessage = null,
            updatedAt = System.currentTimeMillis()))
        // Default to wifi-only (safe default) since DownloadTaskEntity doesn't persist wifiOnly
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        WorkManager.getInstance(app).enqueue(
            OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .setInputData(Data.Builder()
                    .putString(ChapterDownloadWorker.KEY_TASK_ID, task.id)
                    .putString(ChapterDownloadWorker.KEY_MANGA_ID, task.mangaId)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, task.chapterUrl)
                    .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, task.chapterTitle)
                    .putString(ChapterDownloadWorker.KEY_REFERER, task.referer)
                    .putString(ChapterDownloadWorker.KEY_TARGET_DIR, task.targetDir)
                    .putStringArray(
                        ChapterDownloadWorker.KEY_PAGES,
                        runCatching {
                            val arr = JSONArray(task.pagesJson)
                            Array(arr.length()) { idx -> arr.getString(idx) }
                        }.getOrDefault(emptyArray())
                    )
                    .build())
                .setConstraints(constraints)
                .addTag(taskId)
                .addTag("manga_${task.mangaId}")
                .build()
        )
    }

    suspend fun clearCompleted() = downloadTaskDao.clearCompleted()

    suspend fun getDownloadedChapterDir(mangaId: String, chapterUrl: String): String? =
        downloadTaskDao.getLatestByChapter(chapterUrl, mangaId)
            ?.targetDir
            ?.takeIf { File(it).exists() && File(it, ".completed").exists() }

    /**
     * Delete ALL downloaded content for a manga: files on disk, task records,
     * and the downloaded_manga metadata row.
     */
    suspend fun deleteDownloadedManga(mangaId: String) {
        // Cancel any active work
        WorkManager.getInstance(app).cancelAllWorkByTag("manga_$mangaId")
        // Delete files
        canonicalMangaDir(mangaId).deleteRecursively()
        downloadedMangaDao.get(mangaId)?.title?.let { title ->
            DownloadStorage.legacyMangaDir(downloadsRoot, title)?.deleteRecursively()
        }
        // Remove DB records
        downloadTaskDao.deleteByMangaId(mangaId)
        downloadedMangaDao.delete(mangaId)
    }

    suspend fun deleteDownloadedChapterDir(mangaId: String, targetDir: String) {
        val dir = File(targetDir)
        if (dir.exists()) dir.deleteRecursively()
        refreshDownloadedCount(mangaId)
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
                // Update localCoverPath in DB
                downloadedMangaDao.upsert(metadata.copy(localCoverPath = coverFile.absolutePath))
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
        }
    }

}
