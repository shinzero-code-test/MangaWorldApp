package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.script.OkHttpScriptFetcher
import com.exapps.mangaworld.core.source.script.ScriptCookieJar
import com.exapps.mangaworld.core.source.script.ScriptHttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 script-transport gates over a stubbed interceptor (no sockets): the
 * production fetcher validates every hop, isolates cookies per hop, enforces
 * the byte budget, and passes terminal statuses through for the bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpScriptFetcherTest {

    private val hosts = setOf("cdn.example")

    private fun client(
        handler: (Request) -> Response,
        seen: MutableList<Request> = mutableListOf()
    ) = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            seen += chain.request()
            handler(chain.request())
        }
        .build()

    private fun response(
        req: Request,
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): Response {
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
        headers.forEach { (k, v) -> builder.header(k, v) }
        // Bodiless codes must not carry one (OkHttp forbids it, like prod).
        if (code != 204 && code != 304) {
            builder.body(body.toResponseBody("text/html".toMediaType()))
        }
        return builder.build()
    }

    private fun fetcher(
        client: OkHttpClient,
        cookies: Map<String, String> = emptyMap()
    ) = OkHttpScriptFetcher(
        client = client,
        cookieJar = ScriptCookieJar { url ->
            cookies.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value
        },
        io = kotlinx.coroutines.Dispatchers.Unconfined
    )

    private suspend fun OkHttpScriptFetcher.get(
        url: String,
        maxBytes: Long = 1024
    ) = fetch(url, emptyMap(), maxBytes, 15_000, hosts)

    @Test
    fun happyPathReturnsBody() = runTest {
        val c = client({ req -> response(req, 200, body = "hi".toByteArray()) })
        val out = fetcher(c).get("https://cdn.example/a")
        assertEquals("hi", out.body.toString(Charsets.UTF_8))
        assertEquals(200, out.status)
    }

    @Test
    fun terminalStatusPassesThrough() = runTest {
        val c = client({ req -> response(req, 404) })
        val out = fetcher(c).get("https://cdn.example/nope")
        // The bridge maps statuses; the fetcher only moves bytes.
        assertEquals(404, out.status)
    }

    @Test
    fun crossHostEscapeRejectedBeforeRequest() = runTest {
        val seen = mutableListOf<Request>()
        val c = client(
            { req -> response(req, 302, mapOf("Location" to "https://evil.example/b")) },
            seen
        )
        try {
            fetcher(c).get("https://cdn.example/a")
            fail("expected rejection")
        } catch (e: ScriptHttpException) {
            assertTrue(e.message!!.contains("HOST_NOT_ALLOWED"))
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun downgradeRejected() = runTest {
        val seen = mutableListOf<Request>()
        val c = client(
            { req -> response(req, 302, mapOf("Location" to "http://cdn.example/b")) },
            seen
        )
        try {
            fetcher(c).get("https://cdn.example/a")
            fail("expected rejection")
        } catch (e: ScriptHttpException) {
            assertTrue(e.message!!.contains("NOT_HTTPS"))
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun hopBudgetExceeded() = runTest {
        var n = 0
        val c = client({ req -> response(req, 302, mapOf("Location" to "/r${n++}")) })
        try {
            fetcher(c).get("https://cdn.example/start")
            fail("expected budget rejection")
        } catch (e: ScriptHttpException) {
            assertTrue(e.message!!.contains("HOP_BUDGET_EXCEEDED"))
        }
    }

    @Test
    fun oversizedBodyRefused() = runTest {
        val c = client({ req -> response(req, 200, body = ByteArray(100)) })
        try {
            fetcher(c).get("https://cdn.example/big", maxBytes = 10)
            fail("expected TooLarge")
        } catch (e: ScriptHttpException) {
            assertTrue(e.message!!.contains("exceeds budget"))
        }
    }

    @Test
    fun cookiesResolvedPerHopNeverForwarded() = runTest {
        val seen = mutableListOf<Request>()
        var n = 0
        val c = client(
            { req ->
                if (n++ == 0) response(req, 302, mapOf("Location" to "/b"))
                else response(req, 200, body = "ok".toByteArray())
            },
            seen
        )
        val base = "https://cdn.example"
        fetcher(
            c,
            cookies = mapOf("$base/a" to "first=1", "$base/b" to "second=2")
        ).fetch("$base/a", emptyMap(), 1024, 15_000, hosts)
        assertEquals(2, seen.size)
        assertEquals("first=1", seen[0].header("Cookie"))
        assertEquals("second=2", seen[1].header("Cookie"))
    }

    @Test
    fun initialInsecureUrlRefused() = runTest {
        val seen = mutableListOf<Request>()
        val c = client({ req -> response(req, 200, body = "x".toByteArray()) }, seen)
        try {
            fetcher(c).fetch("http://cdn.example/a", emptyMap(), 1024, 15_000, hosts)
            fail("expected https enforcement")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("https"))
        }
        assertEquals(0, seen.size)
    }
}
