package com.exapps.mangaworld.core.data.download

import android.app.Application
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.dao.DownloadBatchDao
import com.exapps.mangaworld.core.data.local.dao.DownloadTaskDao
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadBatchEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.domain.model.ChapterPage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * U-33 download-engine net: the REAL [DownloadQueueManager] and the REAL
 * [DownloadStorage] path math — driven through faithful in-memory DAO
 * fakes and a fake OkHttp interceptor. No mocks of the code under test.
 *
 * Why fakes instead of in-memory Room: Room needs a real Context +
 * framework SQLite, so JVM tests cannot open it without Robolectric (not a
 * test dependency today; adding an unverifiable dependency in CI-only land
 * is riskier than a faithful fake). The fakes mirror the DAO SQL guards in
 * Daos.kt line-for-line (state allow-lists, claim/mark semantics,
 * clearCompleted/dismiss predicates) and the "fake dao" section below pins
 * that mirror so manager tests rest on verified semantics. When Robolectric
 * lands, port these cases to `Room.inMemoryDatabaseBuilder` so the SQL (not
 * the fake) is under test, and retire the fakes.
 *
 * Seam boundary (documented, not faked): every path that reaches the
 * `WorkManager.getInstance(...)` static — enqueue success, batch happy
 * path, retry/resume/pause/cancel, deleteDownloadedManga teardown — throws
 * on JVM (android.jar is stubs-only) and is therefore EXCLUDED below with
 * TODOs. Do not "fix" that with mockkStatic(WorkManager): the static-init +
 * Operation-future plumbing would make the test prove the mock. The proper
 * refactor is an injected enqueue/cancel seam on the manager.
 * `ReaderViewModel.loadChapter` is likewise never called (AGENTS.md wedge).
 */
class DownloadEngineTest {

    // ─── Faithful in-memory DAOs (mirror Daos.kt SQL semantics) ────────────

    /** Mirror of `DownloadTaskDao` in Daos.kt; notes cite the SQL they mirror. */
    class FakeDownloadTaskDao : DownloadTaskDao {
        private val lock = Any()
        private val rows = LinkedHashMap<String, DownloadTaskEntity>()
        private val _all = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())

        /** Test-only snapshot (all rows, insertion order). */
        fun snapshot(): List<DownloadTaskEntity> = synchronized(lock) { rows.values.toList() }

        private fun publish() {
            // observeAll(): ORDER BY createdAt DESC
            _all.value = synchronized(lock) { rows.values.sortedByDescending { it.createdAt } }
        }

        override fun observeAll(): Flow<List<DownloadTaskEntity>> = _all

        override fun observeById(id: String): Flow<DownloadTaskEntity?> =
            kotlinx.coroutines.flow.flow { emit(snapshot().firstOrNull { it.id == id }) }

        override suspend fun getById(id: String): DownloadTaskEntity? = synchronized(lock) { rows[id] }

        // ORDER BY createdAt ASC, id ASC over INCOMPLETE statuses.
        override suspend fun getIncompleteByMangaId(mangaId: String): List<DownloadTaskEntity> =
            synchronized(lock) {
                rows.values
                    .filter { it.mangaId == mangaId && it.status in INCOMPLETE }
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
            }

        override suspend fun getQueuedByMangaId(mangaId: String): List<DownloadTaskEntity> =
            synchronized(lock) {
                rows.values
                    .filter { it.mangaId == mangaId && it.status == "queued" }
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
            }

        override suspend fun getAllIncomplete(): List<DownloadTaskEntity> = synchronized(lock) {
            rows.values.filter { it.status in INCOMPLETE }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        }

        override suspend fun getAllPaused(): List<DownloadTaskEntity> = synchronized(lock) {
            rows.values.filter { it.status == "paused" }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        }

        override suspend fun getPendingByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity? =
            synchronized(lock) {
                rows.values.firstOrNull {
                    it.chapterUrl == chapterUrl && it.mangaId == mangaId && it.status in INCOMPLETE
                }
            }

        override suspend fun getLatestByChapter(chapterUrl: String, mangaId: String): DownloadTaskEntity? =
            synchronized(lock) {
                rows.values
                    .filter { it.chapterUrl == chapterUrl && it.mangaId == mangaId }
                    .maxByOrNull { it.updatedAt }
            }

        // REPLACE semantics.
        override suspend fun upsert(task: DownloadTaskEntity) {
            synchronized(lock) { rows[task.id] = task }
            publish()
        }

        override suspend fun upsertAll(tasks: List<DownloadTaskEntity>) {
            synchronized(lock) { tasks.forEach { rows[it.id] = it } }
            publish()
        }

        // Daos.kt: UPDATE ... WHERE id = :id AND status IN ('queued','running')
        override suspend fun updateStateIfActive(
            id: String, status: String, progress: Float, downloadedPages: Int,
            totalPages: Int, updatedAt: Long, errorMessage: String?
        ): Int = synchronized(lock) {
            val current = rows[id]
            if (current == null || current.status !in ACTIVE) return@synchronized 0
            rows[id] = current.copy(
                status = status, progress = progress, downloadedPages = downloadedPages,
                totalPages = totalPages, updatedAt = updatedAt, errorMessage = errorMessage
            )
            1
        }.also { if (it == 1) publish() }

        // Daos.kt: same guard, plus retries set.
        override suspend fun updateFailureStateIfActive(
            id: String, status: String, retries: Int, errorMessage: String, updatedAt: Long
        ): Int = synchronized(lock) {
            val current = rows[id]
            if (current == null || current.status !in ACTIVE) return@synchronized 0
            rows[id] = current.copy(status = status, retries = retries, errorMessage = errorMessage, updatedAt = updatedAt)
            1
        }.also { if (it == 1) publish() }

        // Daos.kt: single-owner claim — only `queued` can claim; resets progress.
        override suspend fun claimRunningIfQueued(id: String, totalPages: Int, updatedAt: Long): Int =
            synchronized(lock) {
                val current = rows[id]
                if (current == null || current.status != "queued") return@synchronized 0
                rows[id] = current.copy(
                    status = "running", progress = 0f, downloadedPages = 0,
                    totalPages = totalPages, updatedAt = updatedAt, errorMessage = null
                )
                1
            }.also { if (it == 1) publish() }

        // Daos.kt: UPDATE ... SET failureNotified = 1 WHERE failureNotified = 0
        override suspend fun markFailureNotified(id: String, updatedAt: Long): Int =
            synchronized(lock) {
            val current = rows[id]
            if (current == null || current.failureNotified) return@synchronized 0
            rows[id] = current.copy(failureNotified = true, updatedAt = updatedAt)
            1
        }.also { if (it == 1) publish() }

        override suspend fun delete(id: String) {
            synchronized(lock) { rows.remove(id) }
            publish()
        }

        /**
         * Daos.kt clearCompleted(): finished rows only, and only once the
         * user has seen the terminal state — batch completion notified, or
         * single-task failure notified. Single-task `completed` rows have
         * no flag and clear unconditionally.
         */
        override suspend fun clearCompleted() {
            val idsToKeep = mutableSetOf<String>()
            for (t in snapshot()) {
                val keep = when (t.status) {
                    "completed" -> t.batchId != null && !batchNotified(t.batchId)
                    "failed", "cancelled" ->
                        if (t.batchId != null) !batchNotified(t.batchId) else !t.failureNotified
                    else -> true // non-terminal always kept
                }
                if (keep) idsToKeep += t.id
            }
            synchronized(lock) { rows.entries.removeIf { it.key !in idsToKeep } }
            publish()
        }

        // Daos.kt: DELETE ... WHERE id IN (:ids) AND status IN terminal.
        override suspend fun deleteByIds(ids: List<String>) {
            synchronized(lock) {
                ids.forEach { id ->
                    val t = rows[id]
                    if (t != null && t.status in TERMINAL) rows.remove(id)
                }
            }
            publish()
        }

        override suspend fun deleteByMangaId(mangaId: String) {
            synchronized(lock) { rows.entries.removeIf { it.value.mangaId == mangaId } }
            publish()
        }

        /** Test-only hook: does the batch's terminal summary exist yet? */
        var batchNotified: suspend (String) -> Boolean = { false }

        companion object {
            private val INCOMPLETE = setOf("queued", "running", "paused")
            private val ACTIVE = setOf("queued", "running")
            private val TERMINAL = setOf("completed", "failed", "cancelled")
        }
    }

    /** Mirror of `DownloadBatchDao` in Daos.kt. */
    class FakeDownloadBatchDao : DownloadBatchDao {
        private val lock = Any()
        private val rows = LinkedHashMap<String, DownloadBatchEntity>()

        /** Test-only hook: all tasks (any status) of the same fake world. */
        var allTasks: () -> List<DownloadTaskEntity> = { emptyList() }

        override suspend fun getById(id: String): DownloadBatchEntity? = synchronized(lock) { rows[id] }

        override suspend fun upsert(batch: DownloadBatchEntity) {
            synchronized(lock) { rows[batch.id] = batch }
        }

        /** Daos.kt synchronizeOutcomeCounts: recount terminal tasks for the batch. */
        override suspend fun synchronizeOutcomeCounts(id: String, updatedAt: Long) {
            val tasks = allTasks().filter { it.batchId == id }
            synchronized(lock) {
                val current = rows[id] ?: return
                rows[id] = current.copy(
                    completedChapters = tasks.count { it.status == "completed" },
                    failedChapters = tasks.count { it.status in setOf("failed", "cancelled") },
                    updatedAt = updatedAt
                )
            }
        }

        /** Daos.kt claimTerminalNotification: atomic 0→1 once counts reach the total. */
        override suspend fun claimTerminalNotification(id: String, updatedAt: Long): Int =
            synchronized(lock) {
                val current = rows[id] ?: return@synchronized 0
                if (current.completionNotified) return@synchronized 0
                if (current.completedChapters + current.failedChapters < current.totalChapters) return@synchronized 0
                rows[id] = current.copy(completionNotified = true, updatedAt = updatedAt)
                1
            }

        override suspend fun deleteByMangaId(mangaId: String) {
            synchronized(lock) { rows.entries.removeIf { it.value.mangaId == mangaId } }
        }

        /** Daos.kt deleteTerminalBefore: delete notified rows older than the cutoff. */
        override suspend fun deleteTerminalBefore(cutoffMs: Long): Int =
            synchronized(lock) {
                val before = rows.size
                rows.entries.removeIf { it.value.completionNotified && it.value.updatedAt < cutoffMs }
                before - rows.size
            }
    }

    /** Mirror of `DownloadedMangaDao` in Daos.kt. */
    class FakeDownloadedMangaDao : DownloadedMangaDao {
        private val lock = Any()
        private val rows = LinkedHashMap<String, DownloadedMangaEntity>()
        private val _all = MutableStateFlow<List<DownloadedMangaEntity>>(emptyList())

        private fun publish() {
            _all.value = synchronized(lock) { rows.values.sortedByDescending { it.lastUpdatedAt } }
        }

        override fun observeAll(): Flow<List<DownloadedMangaEntity>> = _all
        override suspend fun get(mangaId: String): DownloadedMangaEntity? = synchronized(lock) { rows[mangaId] }

        override suspend fun upsert(entity: DownloadedMangaEntity) {
            synchronized(lock) { rows[entity.mangaId] = entity }
            publish()
        }

        // Daos.kt: targeted UPDATE downloadedChapters (never whole-entity).
        override suspend fun updateChapterCount(mangaId: String, count: Int, now: Long) {
            synchronized(lock) { rows[mangaId]?.let { rows[mangaId] = it.copy(downloadedChapters = count, lastUpdatedAt = now) } }
            publish()
        }

        // Daos.kt: targeted UPDATE localCoverPath (U-12 anti-clobber contract).
        override suspend fun updateCoverPath(mangaId: String, path: String) {
            synchronized(lock) { rows[mangaId]?.let { rows[mangaId] = it.copy(localCoverPath = path) } }
            publish()
        }

        override suspend fun getAll(): List<DownloadedMangaEntity> = synchronized(lock) { rows.values.toList() }

        override suspend fun delete(mangaId: String) {
            synchronized(lock) { rows.remove(mangaId) }
            publish()
        }
    }

    // ─── Harness ────────────────────────────────────────────────────────────

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var externalFiles: File // app.getExternalFilesDir(null)
    private lateinit var taskDao: FakeDownloadTaskDao
    private lateinit var batchDao: FakeDownloadBatchDao
    private lateinit var mangaDao: FakeDownloadedMangaDao
    private lateinit var manager: DownloadQueueManager
    private lateinit var httpResponses: MutableMap<String, Pair<Int, ByteArray>>
    private lateinit var interceptor: FakeImageInterceptor

    private val jpegBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        ByteArray(60) { 0x11 }
    private val htmlBytes: ByteArray = "<!doctype html><html><body>blocked</body></html>".toByteArray(Charsets.UTF_8)

    /** Serves canned (status, bytes) by exact URL; unknown URLs 404. */
    class FakeImageInterceptor(
        private val responses: MutableMap<String, Pair<Int, ByteArray>>
    ) : Interceptor {
        val requestedUrls = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestedUrls += request.url.toString()
            val (code, bytes) = responses[request.url.toString()] ?: (404 to "nf".toByteArray(Charsets.UTF_8))
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
    }

    @Before
    fun setUp() {
        externalFiles = tmp.newFolder("external")
        taskDao = FakeDownloadTaskDao()
        batchDao = FakeDownloadBatchDao()
        mangaDao = FakeDownloadedMangaDao()
        httpResponses = mutableMapOf()
        interceptor = FakeImageInterceptor(httpResponses)
        // Wire the two fakes to the same world (what SQL subselects would see).
        batchDao.allTasks = { taskDao.snapshot() }
        taskDao.batchNotified = { batchId -> batchDao.getById(batchId)?.completionNotified == true }

        val app = mockk<Application>(relaxed = true)
        every { app.getExternalFilesDir(any()) } returns externalFiles

        manager = DownloadQueueManager(
            app = app,
            database = mockk<MangaDatabase>(relaxed = true),
            downloadTaskDao = taskDao,
            downloadBatchDao = batchDao,
            downloadedMangaDao = mangaDao,
            okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            analyticsManager = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true)
        )
    }

    private fun downloadsRoot(): File = File(externalFiles, "downloads")

    private fun chapterDir(mangaId: String, chapterUrl: String): File =
        DownloadStorage.canonicalChapterDir(downloadsRoot(), mangaId, chapterUrl)

    private fun completedChapterDir(mangaId: String, chapterUrl: String, pages: List<String>): File {
        val dir = chapterDir(mangaId, chapterUrl)
        dir.mkdirs()
        pages.forEach { name -> File(dir, name).writeBytes(jpegBytes) }
        File(dir, ".completed").writeText("ok")
        return dir
    }

    private fun pagesJson(urls: List<String>): String {
        val sb = StringBuilder("[")
        urls.forEachIndexed { i, u -> if (i > 0) sb.append(','); sb.append('"').append(u).append('"') }
        return sb.append(']').toString()
    }

    private fun task(
        id: String = "t1",
        mangaId: String = "azora_solo",
        chapterUrl: String = "https://x.com/solo/12",
        status: String = "queued",
        batchId: String? = null,
        pages: List<String> = listOf("https://cdn.x.com/p1.jpg")
    ) = DownloadTaskEntity(
        id = id,
        mangaId = mangaId,
        mangaTitle = "Solo",
        chapterUrl = chapterUrl,
        chapterTitle = "Ch 12",
        sourceId = "azora",
        batchId = batchId,
        targetDir = chapterDir(mangaId, chapterUrl).absolutePath,
        pagesJson = pagesJson(pages),
        status = status,
        totalPages = pages.size
    )

    private fun mangaRow(
        mangaId: String = "azora_solo",
        downloadedChapters: Int = 0,
        title: String = "Solo"
    ) = DownloadedMangaEntity(
        mangaId = mangaId,
        slug = "solo",
        title = title,
        coverUrl = "https://cdn.x.com/cover.jpg",
        sourceId = "azora",
        totalChapters = 10,
        downloadedChapters = downloadedChapters
    )

    private fun writeMetadata(dir: File, mangaId: String, title: String = "Solo") {
        dir.mkdirs()
        File(dir, "metadata.json").writeText(
            JSONObject()
                .put("mangaId", mangaId)
                .put("slug", "solo")
                .put("title", title)
                .put("source", "azora")
                .put("coverUrl", "https://cdn.x.com/cover.jpg")
                .put("totalChapters", 10)
                .put("downloadedAt", 123L)
                .toString(2)
        )
    }

    // ─── Reader seam: downloaded-page URI derivation (image mapping) ───────
    // Doubles as U-33 "page-level resume": this file→ChapterPage mapping is
    // what resume relies on to know which pages are already on disk.

    @Test
    fun `local pages sort numerically, derive file URIs, and require the completion marker`() {
        val url = "https://x.com/solo/12"
        // Lexical order would be 1,10,2 — numeric must win. The zero-length
        // 7.jpg IS served: the read path filters by extension only (length
        // is enforced by the worker at completion time via MIN_PAGE_BYTES).
        completedChapterDir("azora_solo", url, listOf("10.jpg", "1.png", "2.webp", "3.txt", "4.jpg.part", "5.gif", "6.jpg", "7.jpg"))
        File(chapterDir("azora_solo", url), "7.jpg").writeBytes(ByteArray(0))

        val pages = manager.getLocalChapterPages("azora_solo", url)

        assertEquals(5, pages.size) // 1,2,6,7,10 — .txt/.part/.gif excluded
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.index })
        assertTrue(pages[0].url.endsWith("/1.png"))
        assertTrue(pages[1].url.endsWith("/2.webp"))
        assertTrue(pages[2].url.endsWith("/6.jpg"))
        assertTrue(pages[3].url.endsWith("/7.jpg"))
        assertTrue(pages[4].url.endsWith("/10.jpg"))
        assertTrue(pages.all { it.url.startsWith("file:") })
        assertTrue(manager.isChapterDownloaded("azora_solo", url))
    }

    @Test
    fun `crash mid-part leaves the chapter invisible until completion`() {
        val url = "https://x.com/solo/12"
        // Worker crashed halfway: one finished page, one torn .part, no marker.
        val dir = chapterDir("azora_solo", url)
        dir.mkdirs()
        File(dir, "1.jpg").writeBytes(jpegBytes)
        File(dir, "2.jpg.part").writeBytes(jpegBytes.copyOf(10))
        assertFalse(manager.isChapterDownloaded("azora_solo", url))
        assertTrue(manager.getLocalChapterPages("azora_solo", url).isEmpty())

        // Completion without the .part being finished: resume serves only
        // final files — the stale .part is never upgraded to a page.
        File(dir, ".completed").writeText("ok")
        val pages = manager.getLocalChapterPages("azora_solo", url)
        assertEquals(1, pages.size)
        assertTrue(pages.single().url.endsWith("/1.jpg"))
        assertTrue(manager.isChapterDownloaded("azora_solo", url))
    }

    @Test
    fun `completed marker without any image file is not downloaded`() {
        val url = "https://x.com/solo/12"
        val dir = chapterDir("azora_solo", url)
        dir.mkdirs()
        File(dir, ".completed").writeText("ok")
        assertFalse(manager.isChapterDownloaded("azora_solo", url))
        assertTrue(manager.getLocalChapterPages("azora_solo", url).isEmpty())
    }

    @Test
    fun `image extension check is case insensitive`() {
        val url = "https://x.com/solo/12"
        completedChapterDir("azora_solo", url, listOf("1.JPG", "2.PNG"))
        val pages = manager.getLocalChapterPages("azora_solo", url)
        assertEquals(2, pages.size)
        assertTrue(manager.isChapterDownloaded("azora_solo", url))
    }

    @Test
    fun `batch GC deletes only notified rows past the cutoff`() = runBlocking {
        val now = System.currentTimeMillis()
        batchDao.upsert(DownloadBatchEntity(
            id = "old-notified", mangaId = "azora_solo", mangaTitle = "Solo",
            totalChapters = 2, completedChapters = 2, completionNotified = true,
            createdAt = now - 30L * 24 * 60 * 60 * 1000, updatedAt = now - 30L * 24 * 60 * 60 * 1000
        ))
        batchDao.upsert(DownloadBatchEntity(
            id = "recent-notified", mangaId = "azora_solo", mangaTitle = "Solo",
            totalChapters = 2, completedChapters = 2, completionNotified = true,
            createdAt = now - 60_000L, updatedAt = now - 60_000L
        ))
        batchDao.upsert(DownloadBatchEntity(
            id = "old-unnotified", mangaId = "azora_solo", mangaTitle = "Solo",
            totalChapters = 2, completedChapters = 1, completionNotified = false,
            createdAt = now - 30L * 24 * 60 * 60 * 1000, updatedAt = now - 30L * 24 * 60 * 60 * 1000
        ))

        // Default 7-day retention: only the stale notified row goes.
        assertEquals(1, manager.gcTerminalBatches())
        assertNull(batchDao.getById("old-notified"))
        assertNotNull(batchDao.getById("recent-notified"))
        assertNotNull(batchDao.getById("old-unnotified"))
    }

    // ─── Idempotent enqueue (dead-end paths; success needs WorkManager) ────
    // TODO(U-33 follow-up): enqueue success + the racing-winner needs an
    // injectable WorkManager seam (see file header). The reachable contract
    // is the dedup predicate + pending lookup under queueMutex, which is
    // what guarantees a duplicate durable row can never be admitted.

    @Test
    fun `enqueueAndRun rejects empty page lists`() = runBlocking {
        val enqueued = manager.enqueueAndRun(
            taskId = "t1", mangaId = "azora_solo", mangaTitle = "Solo",
            chapterUrl = "https://x.com/solo/12", chapterTitle = "Ch 12",
            pages = emptyList()
        )
        assertFalse(enqueued)
        assertNull(taskDao.getById("t1"))
    }

    @Test
    fun `enqueueAndRun rejects a chapter that is already on disk`() = runBlocking {
        val url = "https://x.com/solo/12"
        completedChapterDir("azora_solo", url, listOf("1.jpg"))
        val enqueued = manager.enqueueAndRun(
            taskId = "t1", mangaId = "azora_solo", mangaTitle = "Solo",
            chapterUrl = url, chapterTitle = "Ch 12",
            pages = listOf(ChapterPage(0, "https://cdn.x.com/p1.jpg"))
        )
        assertFalse(enqueued)
        assertNull(taskDao.getById("t1"))
    }

    @Test
    fun `enqueueAndRun rejects a second request for a pending chapter`() = runBlocking {
        val url = "https://x.com/solo/12"
        taskDao.upsert(task(id = "t1", chapterUrl = url, status = "queued"))
        assertEquals("t1", manager.pendingTaskId("azora_solo", url))
        val enqueued = manager.enqueueAndRun(
            taskId = "t2", mangaId = "azora_solo", mangaTitle = "Solo",
            chapterUrl = url, chapterTitle = "Ch 12",
            pages = listOf(ChapterPage(0, "https://cdn.x.com/p1.jpg"))
        )
        assertFalse(enqueued)
        assertNull(taskDao.getById("t2"))
        assertEquals("queued", taskDao.getById("t1")?.status)
    }

    @Test
    fun `enqueueAndRun racing duplicates admit exactly one durable row`() = runBlocking {
        val url = "https://x.com/solo/12"
        val pages = listOf(ChapterPage(0, "https://cdn.x.com/p1.jpg"))
        // Both callers race check-then-insert. queueMutex must serialize
        // them so exactly one row exists. (The winner's WorkManager call
        // throws on JVM — documented seam limit — so we assert on the
        // durable row, which is written before any WorkManager touch.)
        val jobs = listOf("t1", "t2").map { id ->
            async {
                runCatching {
                    manager.enqueueAndRun(
                        taskId = id, mangaId = "azora_solo", mangaTitle = "Solo",
                        chapterUrl = url, chapterTitle = "Ch 12",
                        pages = pages
                    )
                }
            }
        }
        jobs.awaitAll()
        val rowsForChapter = taskDao.snapshot().filter { it.chapterUrl == url }
        assertEquals(1, rowsForChapter.size)
        assertEquals("queued", rowsForChapter.single().status)
    }

    @Test
    fun `enqueueBatch with nothing new enqueues zero and creates no batch`() = runBlocking {
        val url = "https://x.com/solo/12"
        completedChapterDir("azora_solo", url, listOf("1.jpg"))
        val n = manager.enqueueBatch(
            mangaId = "azora_solo", mangaTitle = "Solo", mangaSlug = "solo",
            sourceId = "azora",
            ready = listOf(
                PreparedChapterDownload(
                    chapterUrl = url, chapterTitle = "Ch 12",
                    pages = listOf(ChapterPage(0, "https://cdn.x.com/p1.jpg")), referer = ""
                )
            ),
            failed = emptyList(), wifiOnly = true
        )
        assertEquals(0, n)
        assertTrue(taskDao.snapshot().isEmpty())
    }

    @Test
    fun `clearCompleted honors the notified-only predicate`() = runBlocking {
        // Single-task terminal rows clear when: completed → always;
        // failed/cancelled → only after the failure was notified.
        taskDao.upsert(task(id = "c1", chapterUrl = "https://x.com/a", status = "completed"))
        taskDao.upsert(task(id = "f1", chapterUrl = "https://x.com/b", status = "failed"))
        taskDao.upsert(task(id = "f2", chapterUrl = "https://x.com/c", status = "failed"))
        taskDao.upsert(task(id = "x1", chapterUrl = "https://x.com/d", status = "cancelled"))
        taskDao.upsert(task(id = "q1", chapterUrl = "https://x.com/e", status = "queued"))
        // failureNotified can only be set via the DAO guard.
        taskDao.markFailureNotified("f1")
        taskDao.markFailureNotified("x1")

        manager.clearCompleted()

        assertNull(taskDao.getById("c1"))
        assertNull(taskDao.getById("f1"))
        assertNull(taskDao.getById("x1"))
        assertNotNull(taskDao.getById("f2")) // never notified → kept for its one notification
        assertNotNull(taskDao.getById("q1")) // non-terminal → kept
    }

    @Test
    fun `clearCompleted keeps batch rows until the batch is notified`() = runBlocking {
        val batchId = "batch_1"
        batchDao.upsert(
            DownloadBatchEntity(
                id = batchId, mangaId = "azora_solo", mangaTitle = "Solo",
                totalChapters = 2, completionNotified = false
            )
        )
        taskDao.upsert(task(id = "c1", chapterUrl = "https://x.com/a", status = "completed", batchId = batchId))
        taskDao.upsert(task(id = "f1", chapterUrl = "https://x.com/b", status = "failed", batchId = batchId))

        // Not yet notified → both stay (the batch summary still owes).
        manager.clearCompleted()
        assertNotNull(taskDao.getById("c1"))
        assertNotNull(taskDao.getById("f1"))

        // Simulate the summary having been claimed.
        batchDao.upsert(batchDao.getById(batchId)!!.copy(completionNotified = true))
        manager.clearCompleted()
        assertNull(taskDao.getById("c1"))
        assertNull(taskDao.getById("f1"))
    }

    @Test
    fun `dismissTasks deletes only terminal rows`() = runBlocking {
        taskDao.upsert(task(id = "q1", chapterUrl = "https://x.com/a", status = "queued"))
        taskDao.upsert(task(id = "r1", chapterUrl = "https://x.com/b", status = "running"))
        taskDao.upsert(task(id = "c1", chapterUrl = "https://x.com/c", status = "completed"))
        taskDao.upsert(task(id = "f1", chapterUrl = "https://x.com/d", status = "failed"))
        taskDao.upsert(task(id = "x1", chapterUrl = "https://x.com/e", status = "cancelled"))

        manager.dismissTasks(listOf("q1", "r1", "c1", "f1", "x1"))

        assertNotNull(taskDao.getById("q1"))
        assertNotNull(taskDao.getById("r1"))
        assertNull(taskDao.getById("c1"))
        assertNull(taskDao.getById("f1"))
        assertNull(taskDao.getById("x1"))
    }

    @Test
    fun `dismissTasks with empty list is a no-op`() = runBlocking {
        taskDao.upsert(task(id = "c1", chapterUrl = "https://x.com/a", status = "completed"))
        manager.dismissTasks(emptyList())
        assertNotNull(taskDao.getById("c1"))
    }

    // ─── Cleanup-vs-read interleaving ─────────────────────────────────────
    // deleteDownloadedChapterDir is disk + count, no WorkManager — directly
    // reachable. Interleave reads and deletes to prove readers never observe
    // a half-deleted chapter (counts only .completed dirs, so a mid-delete
    // dir reads as absent, not torn).

    @Test
    fun `delete chapter dir hides pages immediately and updates the count`() = runBlocking {
        val url12 = "https://x.com/solo/12"
        val url13 = "https://x.com/solo/13"
        completedChapterDir("azora_solo", url12, listOf("1.jpg", "2.jpg"))
        completedChapterDir("azora_solo", url13, listOf("1.jpg"))
        mangaDao.upsert(mangaRow(downloadedChapters = 2))

        // Read before delete.
        assertEquals(2, manager.getLocalChapterPages("azora_solo", url12).size)
        assertEquals(2, manager.countDownloadedChapters("azora_solo"))

        // Delete, then re-read the same chapter: invisible, count decremented.
        manager.deleteDownloadedChapterDir("azora_solo", url12)
        assertFalse(chapterDir("azora_solo", url12).exists())
        assertTrue(manager.getLocalChapterPages("azora_solo", url12).isEmpty())
        assertFalse(manager.isChapterDownloaded("azora_solo", url12))
        assertEquals(1, manager.countDownloadedChapters("azora_solo"))
        assertEquals(1, mangaDao.get("azora_solo")?.downloadedChapters)
        // The other chapter is untouched.
        assertEquals(1, manager.getLocalChapterPages("azora_solo", url13).size)
    }

    @Test
    fun `delete chapter dir for an absent chapter is a harmless no-op`() = runBlocking {
        manager.deleteDownloadedChapterDir("azora_solo", "https://x.com/solo/99")
        assertEquals(0, manager.countDownloadedChapters("azora_solo"))
    }

    // ─── Orphan recovery: filesystem ⇄ database reconciliation ────────────

    @Test
    fun `syncFileSystemWithDatabase restores rows for orphaned manga dirs`() = runBlocking {
        val mangaDir = File(downloadsRoot(), "azora_solo")
        completedChapterDir("azora_solo", "https://x.com/solo/12", listOf("1.jpg"))
        completedChapterDir("azora_solo", "https://x.com/solo/13", listOf("1.jpg", "2.jpg"))
        writeMetadata(mangaDir, "azora_solo")

        manager.syncFileSystemWithDatabase()

        val row = mangaDao.get("azora_solo")
        assertNotNull(row)
        assertEquals(2, row!!.downloadedChapters)
        assertEquals(10, row.totalChapters)
        assertEquals("azora", row.sourceId)
        assertEquals("Solo", row.title)
    }

    @Test
    fun `syncFileSystemWithDatabase is idempotent and skips malformed metadata`() = runBlocking {
        val mangaDir = File(downloadsRoot(), "azora_solo")
        completedChapterDir("azora_solo", "https://x.com/solo/12", listOf("1.jpg"))
        writeMetadata(mangaDir, "azora_solo")

        // A second, malformed dir must be skipped without aborting the scan.
        val brokenDir = File(downloadsRoot(), "broken_one")
        brokenDir.mkdirs()
        File(brokenDir, "metadata.json").writeText("{not json")

        // A dir without metadata.json is not an orphan (not imported).
        File(downloadsRoot(), "no_metadata").mkdirs()

        manager.syncFileSystemWithDatabase()
        manager.syncFileSystemWithDatabase() // second run: no duplicates

        assertEquals(1, mangaDao.getAll().size)
        assertEquals(1, mangaDao.get("azora_solo")?.downloadedChapters)
    }

    @Test
    fun `syncFileSystemWithDatabase never overwrites existing rows`() = runBlocking {
        val mangaDir = File(downloadsRoot(), "azora_solo")
        completedChapterDir("azora_solo", "https://x.com/solo/12", listOf("1.jpg"))
        writeMetadata(mangaDir, "azora_solo")
        mangaDao.upsert(mangaRow(downloadedChapters = 7, title = "Existing Title"))

        manager.syncFileSystemWithDatabase()

        val row = mangaDao.get("azora_solo")!!
        assertEquals(7, row.downloadedChapters)
        assertEquals("Existing Title", row.title)
    }

    @Test
    fun `countDownloadedChapters unions canonical and legacy dirs`() = runBlocking {
        // Canonical chapter.
        completedChapterDir("azora_solo", "https://x.com/solo/12", listOf("1.jpg"))
        // Legacy title-based dir (pre-migration) with its own chapter.
        val legacy = File(downloadsRoot(), "Solo Leveling", "13")
        legacy.mkdirs()
        File(legacy, "1.jpg").writeBytes(jpegBytes)
        File(legacy, ".completed").writeText("ok")
        // A non-completed chapter dir must not be counted.
        File(downloadsRoot(), "azora_solo", "14").mkdirs()

        assertEquals(2, manager.countDownloadedChapters("azora_solo", "Solo Leveling"))
        // Without the title, the legacy dir is invisible (same as prod).
        assertEquals(1, manager.countDownloadedChapters("azora_solo"))
    }

    @Test
    fun `getDownloadedChapterDir resolves the legacy title fallback`() = runBlocking {
        val url = "https://x.com/solo/12"
        val legacyChapter = File(downloadsRoot(), "Solo Leveling", "12")
        legacyChapter.mkdirs()
        File(legacyChapter, "1.jpg").writeBytes(jpegBytes)
        File(legacyChapter, ".completed").writeText("ok")
        mangaDao.upsert(mangaRow(title = "Solo Leveling"))

        assertEquals(legacyChapter.canonicalPath, manager.getDownloadedChapterDir("azora_solo", url))
        // No marker → null (a partial chapter has no readable path).
        chapterDir("azora_solo", url).mkdirs()
        assertNull(manager.getDownloadedChapterDir("azora_solo", "https://x.com/solo/99"))
    }

    // ─── Cover download: crash-recovery + targeted-update contracts ───────

    private val coverUrl = "https://cdn.x.com/cover.jpg"

    @Test
    fun `saveCoverAndMetadata writes a real image cover and metadata`() = runBlocking {
        httpResponses[coverUrl] = 200 to jpegBytes
        val dir = File(downloadsRoot(), "azora_solo")
        completedChapterDir("azora_solo", "https://x.com/solo/12", listOf("1.jpg"))
        mangaDao.upsert(mangaRow(downloadedChapters = 7))
        val metadata = mangaRow(downloadedChapters = 0).copy(coverUrl = coverUrl)

        manager.saveCoverAndMetadata(dir, metadata)

        val cover = File(dir, "cover.jpg")
        assertTrue(cover.exists())
        assertTrue(cover.hasImageMagic())
        assertFalse(File(dir, "cover.jpg.part").exists())
        assertEquals(1, interceptor.requestedUrls.size)
        assertEquals(coverUrl, interceptor.requestedUrls.single())
        // Targeted update: the chapter counter a concurrent completion may
        // have bumped is NOT clobbered by this save (U-12 regression guard).
        val row = mangaDao.get("azora_solo")!!
        assertEquals(cover.absolutePath, row.localCoverPath)
        assertEquals(7, row.downloadedChapters)
        // metadata.json lists the completed chapter dirs.
        val json = JSONObject(File(dir, "metadata.json").readText())
        assertEquals("azora_solo", json.getString("mangaId"))
        assertEquals("Solo", json.getString("title"))
        assertEquals(1, json.getJSONArray("downloadedChapters").length())
        assertEquals("12", json.getJSONArray("downloadedChapters").getString(0))
    }

    @Test
    fun `poisoned cover self heals on the next save`() = runBlocking {
        httpResponses[coverUrl] = 200 to jpegBytes
        val dir = File(downloadsRoot(), "azora_solo")
        dir.mkdirs()
        // Previous attempt saved an HTML block page as cover.jpg.
        File(dir, "cover.jpg").writeBytes(htmlBytes)
        val metadata = mangaRow().copy(coverUrl = coverUrl, downloadedChapters = 0)
        mangaDao.upsert(metadata)

        manager.saveCoverAndMetadata(dir, metadata)

        assertTrue(File(dir, "cover.jpg").hasImageMagic())
    }

    @Test
    fun `failed cover leaves no part file and still writes metadata`() = runBlocking {
        httpResponses[coverUrl] = 404 to htmlBytes
        val dir = File(downloadsRoot(), "azora_solo")
        val metadata = mangaRow().copy(coverUrl = coverUrl, downloadedChapters = 0)
        mangaDao.upsert(metadata)

        manager.saveCoverAndMetadata(dir, metadata)

        assertFalse(File(dir, "cover.jpg").exists())
        assertFalse(File(dir, "cover.jpg.part").exists()) // .part cleaned on failure
        assertTrue(File(dir, "metadata.json").exists())
        // updateCoverPath must never fire for a failed download.
        assertNull(mangaDao.get("azora_solo")?.localCoverPath)
    }

    @Test
    fun `blank cover URL writes only metadata`() = runBlocking {
        val dir = File(downloadsRoot(), "azora_solo")
        val metadata = mangaRow().copy(coverUrl = "", downloadedChapters = 0)
        mangaDao.upsert(metadata)

        manager.saveCoverAndMetadata(dir, metadata)

        assertFalse(File(dir, "cover.jpg").exists())
        assertTrue(File(dir, "metadata.json").exists())
    }

    // ─── Orphan-task recovery ordering (DB side only) ─────────────────────
    // recoverOrphanTasks resets running→queued BEFORE any WorkManager call;
    // that ordering is the crash-recovery contract. The WorkManager enqueue
    // itself is the documented seam limit (throws on JVM), so the test
    // asserts the durable reset and lets the enqueue failure propagate.

    @Test
    fun `recoverOrphanTasks resets stranded running rows to queued first`() = runBlocking {
        taskDao.upsert(task(id = "r1", chapterUrl = "https://x.com/a", status = "running"))
        taskDao.upsert(task(id = "q1", chapterUrl = "https://x.com/b", status = "queued"))
        taskDao.upsert(task(id = "c1", chapterUrl = "https://x.com/c", status = "completed"))

        runCatching { manager.recoverOrphanTasks() } // WorkManager statics throw on JVM

        assertEquals("queued", taskDao.getById("r1")?.status)
        assertEquals("queued", taskDao.getById("q1")?.status)
        assertEquals("completed", taskDao.getById("c1")?.status)
    }

    // ─── DAO state-machine contract (fake mirrors Daos.kt SQL) ─────────────
    // The manager's single-owner and active-guard contracts live in the DAO
    // SQL; these tests pin the fake to that SQL so the manager tests above
    // rest on a verified foundation. They fail if the fake drifts from Daos.kt.

    @Test
    fun `fake dao claim is single owner and only from queued`() = runBlocking {
        taskDao.upsert(task(id = "t1", status = "queued"))
        assertEquals(1, taskDao.claimRunningIfQueued("t1", 5, 1L))
        assertEquals(0, taskDao.claimRunningIfQueued("t1", 5, 2L)) // second claimant loses
        val after = taskDao.getById("t1")!!
        assertEquals("running", after.status)
        assertEquals(5, after.totalPages)
        assertNull(after.errorMessage)

        taskDao.upsert(task(id = "c1", status = "completed"))
        assertEquals(0, taskDao.claimRunningIfQueued("c1", 5, 3L)) // completed can't re-claim
    }

    @Test
    fun `fake dao active guards reject terminal rows`() = runBlocking {
        taskDao.upsert(task(id = "c1", status = "completed"))
        assertEquals(0, taskDao.updateStateIfActive("c1", "running", 0.5f, 1, 2, 1L, null))
        assertEquals(0, taskDao.updateFailureStateIfActive("c1", "failed", 1, "download_error", 1L))
        assertEquals("completed", taskDao.getById("c1")?.status)

        taskDao.upsert(task(id = "q1", status = "queued"))
        assertEquals(1, taskDao.updateStateIfActive("q1", "running", 0.5f, 1, 2, 1L, null))
        assertEquals(1, taskDao.updateFailureStateIfActive("q1", "queued", 1, "download_error", 2L))
        val failed = taskDao.getById("q1")!!
        assertEquals(1, failed.retries)
        assertEquals("download_error", failed.errorMessage)
    }

    @Test
    fun `fake dao markFailureNotified is once only`() = runBlocking {
        taskDao.upsert(task(id = "f1", status = "failed"))
        assertEquals(1, taskDao.markFailureNotified("f1"))
        assertEquals(0, taskDao.markFailureNotified("f1"))
    }

    @Test
    fun `batch claim notifies exactly once when the total is reached`() = runBlocking {        batchDao.upsert(
            DownloadBatchEntity(
                id = "b1", mangaId = "azora_solo", mangaTitle = "Solo",
                totalChapters = 2, completedChapters = 1, failedChapters = 1
            )
        )
        assertEquals(1, batchDao.claimTerminalNotification("b1"))
        assertEquals(0, batchDao.claimTerminalNotification("b1"))

        batchDao.upsert(
            DownloadBatchEntity(
                id = "b2", mangaId = "azora_solo", mangaTitle = "Solo",
                totalChapters = 3, completedChapters = 1, failedChapters = 0
            )
        )
        assertEquals(0, batchDao.claimTerminalNotification("b2")) // not terminal yet
    }

    // ─── TODO (U-33 seam limit — WorkManager static) ───────────────────────
    // The following remain UNCOVERED on JVM and need the injected-seam
    // refactor (extract WorkManager enqueue/cancel behind an interface on
    // DownloadQueueManager, then test the manager with a recording fake):
    //   TODO 1. enqueue success path (insert → metadata → enqueueWorker).
    //   TODO 2. enqueueBatch happy path (batch row + tasks + continuation).
    //   TODO 3. retryTask / resumeTask / pauseTask / cancelTask state
    //           machines (resubmitTasks fresh-vs-cached page resolution,
    //           forceFreshPages partial-dir wipe).
    //   TODO 4. deleteDownloadedManga full teardown (files + tasks +
    //           batches + metadata row).
    //   TODO 5. reconcileBatchCompletion terminal notification.
    //   TODO 6. ChapterDownloadWorker.doWork page loop (chunked parallel
    //           download, .part+rename, marker idempotency) — needs an
    //           instrumented/device run or a worker-agnostic download
    //           engine extraction. NEVER call from JVM unit tests.
}
