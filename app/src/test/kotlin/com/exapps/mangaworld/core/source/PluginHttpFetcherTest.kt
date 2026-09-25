package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.sync.OkHttpPluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phase 2B transport gates: validated redirect chains, per-hop cookie
 * isolation, downgrade refusal, hop budget, and body caps.
 *
 * Served by [StubOrigin], a minimal hand-rolled HTTP/1.1 stub (scripted
 * responses in order, full request capture). Deterministic on JVM CI with no
 * framework quirks; `allowInsecure` stays test-only (production builds the
 * fetcher with it false, and redirect hops reject http regardless).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginHttpFetcherTest {

    private data class StubResponse(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0)
    )

    private data class StubRequest(
        val requestLine: String,
        val headers: Map<String, String>
    )

    /** Ordered script + capture. One connection per request (`Connection: close`). */
    private class StubOrigin {
        private val script = ArrayDeque<StubResponse>()
        val seen = mutableListOf<StubRequest>()
        private var server: ServerSocket? = null
        private var thread: Thread? = null

        fun enqueue(status: Int, headers: List<Pair<String, String>> = emptyList(), body: String = "") {
            script += StubResponse(status, headers, body.toByteArray(Charsets.UTF_8))
        }

        fun enqueueBytes(status: Int, body: ByteArray) {
            script += StubResponse(status, emptyList(), body)
        }

        fun start() {
            server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            thread = thread(isDaemon = true, name = "stub-origin") { acceptLoop() }
        }

        fun stop() {
            runCatching { server?.close() }
            thread?.join(2000)
        }

        fun baseUrl(): String = "http://127.0.0.1:${server!!.localPort}"

        fun hosts(): Set<String> = setOf("127.0.0.1")

        private fun acceptLoop() {
            while (server?.isClosed == false) {
                val sock = runCatching { server!!.accept() }.getOrNull() ?: break
                handle(sock)
            }
        }

        private fun handle(sock: Socket) {
            try {
                val reader: BufferedReader = InputStreamReader(sock.getInputStream()).buffered()
                val requestLine = reader.readLine() ?: ""
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val cut = line.indexOf(':')
                    if (cut > 0) headers[line.substring(0, cut).trim().lowercase()] = line.substring(cut + 1).trim()
                }
                synchronized(seen) { seen += StubRequest(requestLine, headers) }
                val resp = synchronized(script) {
                    if (script.isNotEmpty()) script.removeFirst()
                    else StubResponse(500, emptyList(), ByteArray(0))
                }
                val out = sock.getOutputStream()
                val head = buildString {
                    append("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("Connection: close\r\n")
                    resp.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("\r\n")
                }
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(resp.body)
                out.flush()
            } catch (_: Exception) {
                // Client went away; nothing to assert.
            } finally {
                runCatching { sock.close() }
            }
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            302 -> "Found"
            304 -> "Not Modified"
            404 -> "Not Found"
            else -> "Status"
        }
    }

    private fun fetcher(cookies: Map<String, String> = emptyMap()) = OkHttpPluginFetcher(
        callFactory = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        allowInsecure = true,
        cookieHeader = { url ->
            cookies.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value
        }
    )

    @Test
    fun happyChainFollowsSameHostHops() = runTest {
        val server = StubOrigin()
        server.enqueue(302, listOf("Location" to "/b"))
        server.enqueue(200, body = "hello")
        server.start()
        try {
            val out = fetcher().get(
                "${server.baseUrl()}/a", server.hosts(), maxBytes = 1024
            )
            assertEquals("hello", out.body.toString(Charsets.UTF_8))
            assertTrue(out.finalUrl.endsWith("/b"))
            assertEquals(2, server.seen.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun cookiesResolvedPerHopNeverForwarded() = runTest {
        val server = StubOrigin()
        server.enqueue(302, listOf("Location" to "/b"))
        server.enqueue(200, body = "ok")
        server.start()
        try {
            val base = server.baseUrl()
            fetcher(
                cookies = mapOf("$base/a" to "first=1", "$base/b" to "second=2")
            ).get("${base}/a", server.hosts(), maxBytes = 1024)
            assertEquals(2, server.seen.size)
            assertEquals("first=1", server.seen[0].headers["cookie"])
            // Second hop carries ONLY the freshly resolved cookie for /b.
            assertEquals("second=2", server.seen[1].headers["cookie"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun crossHostEscapeRejectedBeforeRequest() = runTest {
        val server = StubOrigin()
        server.enqueue(302, listOf("Location" to "https://evil.example/b"))
        server.start()
        try {
            try {
                fetcher().get("${server.baseUrl()}/a", server.hosts(), maxBytes = 1024)
                fail("expected rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("HOST_NOT_ALLOWED"))
            }
            // The escape hop was never issued.
            assertEquals(1, server.seen.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun redirectDowngradeRejected() = runTest {
        val server = StubOrigin()
        // http hop target: rejected even though the initial URL was permitted
        // via allowInsecure — every hop re-enforces https.
        server.enqueue(302, listOf("Location" to "http://evil.example/b"))
        server.start()
        try {
            try {
                fetcher().get("${server.baseUrl()}/a", server.hosts(), maxBytes = 1024)
                fail("expected rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("NOT_HTTPS"))
            }
            assertEquals(1, server.seen.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun hopBudgetExceeded() = runTest {
        val server = StubOrigin()
        repeat(10) { i -> server.enqueue(302, listOf("Location" to "/r$i")) }
        server.start()
        try {
            try {
                fetcher().get("${server.baseUrl()}/start", server.hosts(), maxBytes = 1024)
                fail("expected budget rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("HOP_BUDGET_EXCEEDED"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun oversizedBodyRefused() = runTest {
        val server = StubOrigin()
        server.enqueueBytes(200, ByteArray(100) { 'x'.code.toByte() })
        server.start()
        try {
            try {
                fetcher().get("${server.baseUrl()}/big", server.hosts(), maxBytes = 10)
                fail("expected TooLarge")
            } catch (e: PluginFetcher.FetchFailure.TooLarge) {
                // Expected.
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun httpStatusSurfaces() = runTest {
        val server = StubOrigin()
        server.enqueue(404)
        server.start()
        try {
            try {
                fetcher().get("${server.baseUrl()}/nope", server.hosts(), maxBytes = 1024)
                fail("expected Http")
            } catch (e: PluginFetcher.FetchFailure.Http) {
                assertEquals(404, e.code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun notModifiedPropagates() = runTest {
        val server = StubOrigin()
        server.enqueue(304)
        server.start()
        try {
            try {
                fetcher().get(
                    "${server.baseUrl()}/idx", server.hosts(), maxBytes = 1024,
                    headers = mapOf("If-None-Match" to "\"v1\"")
                )
                fail("expected NotModified")
            } catch (e: OkHttpPluginFetcher.NotModified) {
                // Expected: the engine translates this to indexNotModified.
            }
            assertEquals(1, server.seen.size)
            assertEquals("\"v1\"", server.seen[0].headers["if-none-match"])
        } finally {
            server.stop()
        }
    }
}
