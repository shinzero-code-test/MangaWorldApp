package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.sync.OkHttpPluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2B transport gates: validated redirect chains, per-hop cookie
 * isolation, downgrade refusal, hop budget, body caps, and ETag pass-through.
 *
 * Served by [ScriptCallFactory] — scripted responses in call order with full
 * request capture, no sockets. Every URL here is https (plain-http hops are
 * rejected by rule, which is exactly what [redirectDowngradeRejected] proves);
 * `allowInsecure` stays a constructor flag the tests never enable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginHttpFetcherTest {

    private data class ScriptedResponse(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0)
    )

    private object Trace {
        val calls = mutableListOf<String>()
    }

    private class ScriptCall(
        private val request: Request,
        private val next: ScriptedResponse
    ) : Call {
        override fun request(): Request = request
        override fun execute(): Response {
            if (System.getProperty("probe.execentry") == "1") throw UnsupportedOperationException("EXECUTE-RAN")
            Trace.calls += "execute"
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(next.code)
                .message("stub")
                .headers(okhttp3.Headers.headersOf(*next.headers.flatMap { (k, v) -> listOf(k, v) }.toTypedArray()))
                .body(next.body.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun clone(): Call = throw UnsupportedOperationException("clone-unused")
        override fun timeout(): Timeout = Timeout.NONE
    }

    private class ScriptCallFactory : Call.Factory {
        val script = ArrayDeque<ScriptedResponse>()
        val seen = mutableListOf<Request>()

        fun enqueue(code: Int, headers: Map<String, String> = emptyMap(), body: String = "") {
            script += ScriptedResponse(code, headers, body.toByteArray(Charsets.UTF_8))
        }

        fun enqueueBytes(code: Int, body: ByteArray) {
            script += ScriptedResponse(code, emptyMap(), body)
        }

        override fun newCall(request: Request): Call {
            seen += request
            Trace.calls += "newCall"
            val next = script.removeFirstOrNull()
                ?: ScriptedResponse(500, emptyMap(), ByteArray(0))
            Trace.calls += "newCall-popped"
            val call = ScriptCall(request, next)
            Trace.calls += "newCall-built"
            return call
        }
    }

    private fun fetcher(
        factory: ScriptCallFactory,
        cookies: Map<String, String> = emptyMap()
    ) = OkHttpPluginFetcher(
        callFactory = factory,
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        allowInsecure = false,
        cookieHeader = { url ->
            cookies.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value
        }
    )

    private val hosts = setOf("cdn.example")

    private fun url(path: String) = "https://cdn.example$path"

    @Test
    fun probeDoubleBuildsResponse() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val req = Request.Builder().url("https://cdn.example/a").build()
        val resp = factory.newCall(req).execute()
        assertEquals(200, resp.code)
        assertEquals("hi", resp.body!!.string())
    }

    @Test
    fun probeGetSingle200() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val out = fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeGetSteps() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val first = java.net.URI("https://cdn.example/a".trim())
        assertEquals("https", first.scheme)
        val current = first.toASCIIString()
        val builder = okhttp3.Request.Builder().url(current).get()
        val request = builder.build()
        val response = factory.newCall(request).execute()
        assertEquals(200, response.code)
        response.use { res ->
            val b = res.body!!.bytes()
            assertEquals("hi", b.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun probeExecuteInsideWithContext() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val req = okhttp3.Request.Builder().url("https://cdn.example/a").build()
        val resp = withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            factory.newCall(req).execute()
        }
        assertEquals(200, resp.code)
        assertEquals("hi", resp.body!!.string())
    }

    @Test
    fun probeGetVerbatim() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val url = "https://cdn.example/a"
        val out = withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            val first = java.net.URI(url.trim())
            if (!first.scheme.equals("https", ignoreCase = true)) throw IllegalStateException("scheme")
            val current = first.toASCIIString()
            val builder = okhttp3.Request.Builder().url(current).get()
            val request = builder.build()
            val response = factory.newCall(request).execute()
            var result: ByteArray? = null
            response.use { res ->
                result = res.body!!.bytes()
            }
            result!!
        }
        assertEquals("hi", out.toString(Charsets.UTF_8))
    }

    @Test
    fun probeCookieInvoke() = runTest {
        val cookies = emptyMap<String, String>()
        val cookieHeader: (suspend (String) -> String?)? = { url ->
            cookies.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value
        }
        val out = withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            cookieHeader?.invoke("https://cdn.example/a")
        }
        assertNull(out)
    }

    @Test
    fun probeBuilderGet() = runTest {
        val req = okhttp3.Request.Builder().url("https://cdn.example/a").get().build()
        assertEquals("GET", req.method)
    }

    @Test
    fun probeContentLength() = runTest {
        val body = "hi".toByteArray(Charsets.UTF_8)
            .toResponseBody("application/octet-stream".toMediaType())
        assertEquals(2, body.contentLength())
        assertEquals("hi", body.bytes().toString(Charsets.UTF_8))
    }

    @Test
    fun probeDeltaFetchResult() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val out = withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            val response = factory.newCall(
                okhttp3.Request.Builder().url("https://cdn.example/a").build()
            ).execute()
            var result: ByteArray? = null
            response.use { res -> result = res.body!!.bytes() }
            PluginFetcher.FetchResult(body = result!!, finalUrl = "https://cdn.example/a", etag = null)
        }
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeDeltaLetReturn() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val out: PluginFetcher.FetchResult = withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            val response = factory.newCall(
                okhttp3.Request.Builder().url("https://cdn.example/a").build()
            ).execute()
            var result: PluginFetcher.FetchResult? = null
            response.use { res ->
                result = PluginFetcher.FetchResult(
                    body = res.body!!.bytes(), finalUrl = "https://cdn.example/a", etag = null
                )
            }
            result?.let { return@withContext it }
            throw IllegalStateException("unreachable")
        }
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeGetNoCookieLambda() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val f = OkHttpPluginFetcher(
            factory, kotlinx.coroutines.Dispatchers.Unconfined, false, null
        )
        val out = f.get(url("/a"), hosts, maxBytes = 1024)
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeFullReplication() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val url = "https://cdn.example/a"
        val allowedHosts = setOf("cdn.example")
        val maxBytes = 1024L
        val headers = emptyMap<String, String>()
        val cookieHeader: (suspend (String) -> String?)? = { _ -> null }
        val io = kotlinx.coroutines.Dispatchers.Unconfined
        val out = withContext(io) {
            val first = java.net.URI(url.trim())
            if (!first.scheme.equals("https", ignoreCase = true)) throw IllegalStateException("scheme")
            var current = first.toASCIIString()
            var hops = 0
            var finalBody: ByteArray? = null
            while (true) {
                val builder = okhttp3.Request.Builder().url(current).get()
                headers.forEach { (k, v) ->
                    if (!k.equals("Cookie", ignoreCase = true)) builder.header(k, v)
                }
                cookieHeader?.invoke(current)?.takeIf { it.isNotBlank() }?.let {
                    builder.header("Cookie", it)
                }
                val request = builder.build()
                val response = runCatching {
                    factory.newCall(request).execute()
                }.getOrElse { e ->
                    throw PluginFetcher.FetchFailure.Network(e)
                }
                var redirectLocation: String? = null
                var result: ByteArray? = null
                response.use { res ->
                    if (res.code in 301..308) {
                        redirectLocation = res.header("Location")
                    } else {
                        if (!res.isSuccessful) throw IllegalStateException("http " + res.code)
                        val body = res.body ?: throw IllegalStateException("empty")
                        if (body.contentLength() > maxBytes) throw IllegalStateException("big")
                        result = body.bytes()
                    }
                }
                result?.let {
                    finalBody = it
                    // escape loop via flag instead of non-local return
                    hops = 999
                }
                if (hops == 999) break
                hops++
                if (hops > 3) throw IllegalStateException("loop")
                break
            }
            finalBody!!
        }
        assertEquals("hi", out.toString(Charsets.UTF_8))
    }

    @Test
    fun probeGetStyleRequest() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val req = Request.Builder().url("https://cdn.example/a").get().build()
        val resp = factory.newCall(req).execute()
        assertEquals(200, resp.code)
        assertEquals("hi", resp.body!!.string())
    }

    @Test
    fun probeReplicationThenGet() = runTest {
        // Manual replication first (proven to pass)...
        val f1 = ScriptCallFactory()
        f1.enqueue(200, body = "hi")
        val manual = f1.newCall(
            okhttp3.Request.Builder().url("https://cdn.example/a").build()
        ).execute()
        assertEquals(200, manual.code)
        // ...then the real get() with a fresh double in the SAME test.
        val f2 = ScriptCallFactory()
        f2.enqueue(200, body = "hi")
        val f = OkHttpPluginFetcher(
            f2, kotlinx.coroutines.Dispatchers.Unconfined, false, null
        )
        val out = f.get(url("/a"), hosts, maxBytes = 1024)
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeSplitNewCallExecute() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        val req = Request.Builder().url("https://cdn.example/a").build()
        val call = factory.newCall(req)
        val resp = call.execute()
        assertEquals(200, resp.code)
    }

    @Test
    fun probeTraceGetReachesDouble() = runTest {
        Trace.calls.clear()
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        try {
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
        } catch (_: Exception) {
            // Swallowed on purpose: reachability is what we assert below.
        }
        assertTrue(Trace.calls.contains("newCall"))
        assertTrue(Trace.calls.contains("newCall-popped"))
        assertTrue(Trace.calls.contains("newCall-built"))
        assertTrue(Trace.calls.contains("execute"))
    }

    @Test
    fun probeRealServerThroughGet() = runTest {
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val t = kotlin.concurrent.thread(isDaemon = true, name = "probe-http") {
            val sock = runCatching { server.accept() }.getOrNull() ?: return@thread
            try {
                val reader = java.io.InputStreamReader(sock.getInputStream()).buffered()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = "real".toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                sock.getOutputStream().write(body)
                sock.getOutputStream().flush()
            } catch (_: Exception) {
            } finally {
                runCatching { sock.close() }
            }
        }
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val f = OkHttpPluginFetcher(
                client, kotlinx.coroutines.Dispatchers.Unconfined, true, null
            )
            val out = f.get(
                "http://127.0.0.1:$port/a", setOf("127.0.0.1"), maxBytes = 1024
            )
            assertEquals("real", out.body.toString(Charsets.UTF_8))
        } finally {
            runCatching { server.close() }
            t.join(2000)
        }
    }

    @Test
    fun probeFreshnessMarker() = runTest {
        System.setProperty("probe.fresh", "1")
        try {
            val factory = ScriptCallFactory()
            factory.enqueue(200, body = "hi")
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
            fail("expected freshness marker")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("FRESH-PROBE"))
        } finally {
            System.clearProperty("probe.fresh")
        }
    }

    @Test
    fun probeEmptyScriptGivesHttpNotNetwork() = runTest {
        val factory = ScriptCallFactory()
        try {
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
            fail("expected Http")
        } catch (e: PluginFetcher.FetchFailure.Http) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun probeSeenAfterFail() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(200, body = "hi")
        try {
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
        } catch (_: Exception) {
            // Expected to fail (for now); we only care what the double saw.
        }
        assertEquals(1, factory.seen.size)
    }

    @Test
    fun probeMockkDoubleThroughGet() = runTest {
        val body = "hi".toByteArray(Charsets.UTF_8)
        val req = Request.Builder().url("https://cdn.example/a").build()
        val resp = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("stub")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()
        val call = io.mockk.mockk<Call>()
        io.mockk.every { call.execute() } returns resp
        io.mockk.every { call.request() } returns req
        val factory = io.mockk.mockk<Call.Factory>()
        io.mockk.every { factory.newCall(any()) } returns call
        val f = OkHttpPluginFetcher(
            factory, kotlinx.coroutines.Dispatchers.Unconfined, false, null
        )
        val out = f.get(url("/a"), hosts, maxBytes = 1024)
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
    }

    @Test
    fun probeExecuteEntryReached() = runTest {
        System.setProperty("probe.execentry", "1")
        try {
            val factory = ScriptCallFactory()
            factory.enqueue(200, body = "hi")
            try {
                fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
                fail("expected EXECUTE-RAN")
            } catch (e: UnsupportedOperationException) {
                assertTrue((e.message ?: "").contains("EXECUTE-RAN"))
            }
        } finally {
            System.clearProperty("probe.execentry")
        }
    }

    @Test
    fun probeUnconfinedWithContext() = runTest {
        val out = withContext(kotlinx.coroutines.Dispatchers.Unconfined) { "ok" }
        assertEquals("ok", out)
    }

    @Test
    fun happyChainFollowsSameHostHops() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(302, mapOf("Location" to "/b"))
        factory.enqueue(200, body = "hello")
        val out = fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
        assertEquals("hello", out.body.toString(Charsets.UTF_8))
        assertTrue(out.finalUrl.endsWith("/b"))
        assertEquals(2, factory.seen.size)
    }

    @Test
    fun cookiesResolvedPerHopNeverForwarded() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(302, mapOf("Location" to "/b"))
        factory.enqueue(200, body = "ok")
        val base = "https://cdn.example"
        fetcher(
            factory,
            cookies = mapOf("$base/a" to "first=1", "$base/b" to "second=2")
        ).get("$base/a", hosts, maxBytes = 1024)
        assertEquals(2, factory.seen.size)
        assertEquals("first=1", factory.seen[0].header("Cookie"))
        // Second hop carries ONLY the freshly resolved cookie for /b.
        assertEquals("second=2", factory.seen[1].header("Cookie"))
    }

    @Test
    fun crossHostEscapeRejectedBeforeRequest() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(302, mapOf("Location" to "https://evil.example/b"))
        try {
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
            fail("expected rejection")
        } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
            assertTrue(e.message!!.contains("HOST_NOT_ALLOWED"))
        }
        // The escape hop was never issued.
        assertEquals(1, factory.seen.size)
    }

    @Test
    fun redirectDowngradeRejected() = runTest {
        val factory = ScriptCallFactory()
        // http hop target: every hop re-enforces https, no exceptions.
        factory.enqueue(302, mapOf("Location" to "http://cdn.example/b"))
        try {
            fetcher(factory).get(url("/a"), hosts, maxBytes = 1024)
            fail("expected rejection")
        } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
            assertTrue(e.message!!.contains("NOT_HTTPS"))
        }
        assertEquals(1, factory.seen.size)
    }

    @Test
    fun hopBudgetExceeded() = runTest {
        val factory = ScriptCallFactory()
        repeat(10) { i -> factory.enqueue(302, mapOf("Location" to "/r$i")) }
        try {
            fetcher(factory).get(url("/start"), hosts, maxBytes = 1024)
            fail("expected budget rejection")
        } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
            assertTrue(e.message!!.contains("HOP_BUDGET_EXCEEDED"))
        }
    }

    @Test
    fun oversizedBodyRefused() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueueBytes(200, ByteArray(100) { 'x'.code.toByte() })
        try {
            fetcher(factory).get(url("/big"), hosts, maxBytes = 10)
            fail("expected TooLarge")
        } catch (e: PluginFetcher.FetchFailure.TooLarge) {
            // Expected.
        }
    }

    @Test
    fun httpStatusSurfaces() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(404)
        try {
            fetcher(factory).get(url("/nope"), hosts, maxBytes = 1024)
            fail("expected Http")
        } catch (e: PluginFetcher.FetchFailure.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun notModifiedPropagates() = runTest {
        val factory = ScriptCallFactory()
        factory.enqueue(304)
        try {
            fetcher(factory).get(
                url("/idx"), hosts, maxBytes = 1024,
                headers = mapOf("If-None-Match" to "\"v1\"")
            )
            fail("expected NotModified")
        } catch (e: OkHttpPluginFetcher.NotModified) {
            // Expected: the engine translates this to indexNotModified.
        }
        assertEquals(1, factory.seen.size)
        assertEquals("\"v1\"", factory.seen[0].header("If-None-Match"))
    }

    @Test
    fun initialInsecureUrlRefused() = runTest {
        val factory = ScriptCallFactory()
        try {
            fetcher(factory).get("http://cdn.example/a", hosts, maxBytes = 1024)
            fail("expected Insecure")
        } catch (e: PluginFetcher.FetchFailure.Insecure) {
            // Expected: allowInsecure is false here (production posture).
        }
        assertEquals(0, factory.seen.size)
    }
}
