package com.exapps.mangaworld.core.data.download

import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * U-33 (TODO 6): the chapter page loop, verified off-device.
 *
 * `ChapterDownloadWorker.doWork` used to own this loop inline, so it could
 * only ever run on a device. The loop now lives in [ChapterDownloadEngine]
 * (worker-agnostic) and this suite drives it with a fake HTTP interceptor —
 * the real engine, the real OkHttp client, the real file layout.
 *
 * What is pinned here:
 * - every page lands as `<n>.jpg` and the chapter gets its `.completed` marker;
 * - a dead page fails the chapter AFTER its siblings had their chance (the
 *   retry path depends on that ordering);
 * - an HTTP-200 HTML challenge page never becomes a page file;
 * - a resumed chapter does not re-download what is already on disk;
 * - the completion marker is idempotent.
 */
class ChapterDownloadEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var targetDir: File
    private lateinit var requestedUrls: MutableList<String>
    private lateinit var responses: MutableMap<String, Pair<Int, ByteArray>>
    private lateinit var engine: ChapterDownloadEngine

    /** ≥1 KiB with JPEG magic: the engine rejects smaller / non-image bodies. */
    private val pageBytes: ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2048) { 0x11 }

    private val htmlBytes: ByteArray =
        "<!doctype html><html><body>blocked</body></html>".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        targetDir = tmp.newFolder("chapter")
        requestedUrls = mutableListOf()
        responses = mutableMapOf()

        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls += url
                val (code, bytes) = responses[url] ?: (404 to "nf".toByteArray(Charsets.UTF_8))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
        ).build()

        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.getCookies(any()) } returns flowOf(null)

        engine = ChapterDownloadEngine(client, settingsRepository)
    }

    @Test
    fun `downloads every page and writes the completion marker`() = runBlocking {
        val pages = listOf(
            "https://cdn.x.com/p1.jpg",
            "https://cdn.x.com/p2.jpg",
            "https://cdn.x.com/p3.jpg"
        )
        pages.forEach { responses[it] = 200 to pageBytes }

        val outcome = engine.downloadChapterPages(pages, referer = "", targetDir = targetDir)

        assertEquals(3, outcome.onDisk)
        assertEquals(3, outcome.total)
        assertTrue(outcome.markedComplete)
        assertEquals(pages, requestedUrls)
        listOf("1.jpg", "2.jpg", "3.jpg").forEach { name ->
            val file = File(targetDir, name)
            assertTrue("$name missing", file.exists())
            assertEquals(pageBytes.size.toLong(), file.length())
        }
        assertTrue(File(targetDir, ChapterDownloadEngine.COMPLETION_MARKER).exists())
        // No staging file is left behind.
        assertFalse(File(targetDir, ChapterDownloadEngine.COMPLETION_MARKER_PART).exists())
        assertFalse(File(targetDir, "1.jpg.part").exists())
    }

    @Test
    fun `a dead page fails the chapter but keeps the good pages on disk`() = runBlocking {
        val pages = listOf(
            "https://cdn.x.com/p1.jpg",
            "https://cdn.x.com/p2.jpg",
            "https://cdn.x.com/p3.jpg"
        )
        responses[pages[0]] = 200 to pageBytes
        responses[pages[2]] = 200 to pageBytes
        // pages[1] stays unstubbed → 404.

        try {
            engine.downloadChapterPages(pages, referer = "", targetDir = targetDir)
            fail("a dead page must fail the chapter")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("incomplete download") == true)
        }

        // The siblings had their chance — their files are on disk.
        assertTrue(File(targetDir, "1.jpg").exists())
        assertTrue(File(targetDir, "3.jpg").exists())
        assertFalse(File(targetDir, "2.jpg").exists())
        // No marker: the chapter is not readable.
        assertFalse(File(targetDir, ChapterDownloadEngine.COMPLETION_MARKER).exists())
    }

    @Test
    fun `an HTML challenge body never becomes a page file`() = runBlocking {
        val url = "https://cdn.x.com/p1.jpg"
        responses[url] = 200 to htmlBytes

        try {
            engine.downloadChapterPages(listOf(url), referer = "", targetDir = targetDir)
            fail("a challenge page must fail the chapter")
        } catch (expected: IllegalStateException) {
            // expected: incomplete download
        }

        assertFalse(File(targetDir, "1.jpg").exists())
        // The rejected body is not left behind as a staging file either.
        assertFalse(File(targetDir, "1.jpg.part").exists())
    }

    @Test
    fun `a resumed chapter does not re-download what is already on disk`() = runBlocking {
        val pages = listOf("https://cdn.x.com/p1.jpg", "https://cdn.x.com/p2.jpg")
        responses[pages[1]] = 200 to pageBytes
        File(targetDir, "1.jpg").writeBytes(pageBytes)

        val outcome = engine.downloadChapterPages(pages, referer = "", targetDir = targetDir)

        assertEquals(2, outcome.onDisk)
        assertTrue(outcome.markedComplete)
        // Only the missing page was fetched.
        assertEquals(listOf(pages[1]), requestedUrls)
    }

    @Test
    fun `the completion marker is idempotent`() = runBlocking {
        val pages = listOf("https://cdn.x.com/p1.jpg")
        responses[pages[0]] = 200 to pageBytes

        val first = engine.downloadChapterPages(pages, referer = "", targetDir = targetDir)
        assertTrue(first.markedComplete)

        // A second run must not fail because the marker already exists.
        val second = engine.downloadChapterPages(pages, referer = "", targetDir = targetDir)
        assertTrue(second.markedComplete)
        assertEquals(1, second.onDisk)
        assertTrue(File(targetDir, ChapterDownloadEngine.COMPLETION_MARKER).exists())
    }

    @Test
    fun `progress is reported for the chapter`() = runBlocking {
        val pages = listOf("https://cdn.x.com/p1.jpg", "https://cdn.x.com/p2.jpg")
        pages.forEach { responses[it] = 200 to pageBytes }
        val seen = mutableListOf<Pair<Int, Int>>()

        engine.downloadChapterPages(
            pages = pages,
            referer = "",
            targetDir = targetDir,
            onProgress = { done, total -> seen += done to total }
        )

        // The first report is the pre-flight count, the last one is the
        // disk-truthful recount.
        assertTrue(seen.isNotEmpty())
        assertEquals(0 to 2, seen.first())
        assertEquals(2 to 2, seen.last())
    }
}
