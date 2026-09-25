package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.sync.OkHttpPluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Phase 2B transport gates over a fake origin: validated redirect chains,
 * per-hop cookie isolation, downgrade refusal, hop budget, and body caps.
 * `allowInsecure` is test-only (MockWebServer is plain http); production
 * builds the fetcher with it false, and redirect hops reject http regardless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginHttpFetcherTest {

    private fun fetcher(
        server: MockWebServer,
        cookies: Map<String, String> = emptyMap()
    ) = OkHttpPluginFetcher(
        callFactory = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        allowInsecure = true,
        cookieHeader = { url ->
            cookies.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value
        }
    )

    private fun hosts(server: MockWebServer) = setOf(server.hostName.lowercase())

    @Test
    fun happyChainFollowsSameHostHops() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/b"))
        server.enqueue(MockResponse().setBody("hello"))
        server.play()
        try {
            val out = fetcher(server).get(
                server.url("/a").toString(), hosts(server), maxBytes = 1024
            )
            assertEquals("hello", out.body.toString(Charsets.UTF_8))
            assertTrue(out.finalUrl.endsWith("/b"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cookiesResolvedPerHopNeverForwarded() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/b"))
        server.enqueue(MockResponse().setBody("ok"))
        server.play()
        try {
            val base = server.url("/").toString().removeSuffix("/")
            val f = fetcher(
                server,
                cookies = mapOf("$base/a" to "first=1", "$base/b" to "second=2")
            )
            f.get(server.url("/a").toString(), hosts(server), maxBytes = 1024)
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("first=1", first.getHeader("Cookie"))
            // Second hop carries ONLY the freshly resolved cookie for /b.
            assertEquals("second=2", second.getHeader("Cookie"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun crossHostEscapeRejectedBeforeRequest() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/b"))
        server.play()
        try {
            try {
                fetcher(server).get(
                    server.url("/a").toString(), hosts(server), maxBytes = 1024
                )
                fail("expected rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("HOST_NOT_ALLOWED"))
            }
            // The escape hop was never issued.
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun redirectDowngradeRejected() = runTest {
        val server = MockWebServer()
        // Same host, http scheme: hop validation rejects the downgrade even
        // though the initial URL was permitted via allowInsecure.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://evil.example/b"))
        server.play()
        try {
            try {
                fetcher(server).get(
                    server.url("/a").toString(), hosts(server), maxBytes = 1024
                )
                fail("expected rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("NOT_HTTPS"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun hopBudgetExceeded() = runTest {
        val server = MockWebServer()
        repeat(10) { i ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/r$i"))
        }
        server.play()
        try {
            try {
                fetcher(server).get(server.url("/start").toString(), hosts(server), maxBytes = 1024)
                fail("expected budget rejection")
            } catch (e: PluginFetcher.FetchFailure.RedirectRejected) {
                assertTrue(e.message!!.contains("HOP_BUDGET_EXCEEDED"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oversizedBodyRefused() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(100)))
        server.play()
        try {
            try {
                fetcher(server).get(server.url("/big").toString(), hosts(server), maxBytes = 10)
                fail("expected TooLarge")
            } catch (e: PluginFetcher.FetchFailure.TooLarge) {
                // Expected.
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpStatusSurfaces() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.play()
        try {
            try {
                fetcher(server).get(server.url("/nope").toString(), hosts(server), maxBytes = 1024)
                fail("expected Http")
            } catch (e: PluginFetcher.FetchFailure.Http) {
                assertEquals(404, e.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun notModifiedPropagates() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304))
        server.play()
        try {
            try {
                fetcher(server).get(
                    server.url("/idx").toString(), hosts(server), maxBytes = 1024,
                    headers = mapOf("If-None-Match" to "\"v1\"")
                )
                fail("expected NotModified")
            } catch (e: OkHttpPluginFetcher.NotModified) {
                // Expected: the engine translates this to indexNotModified.
            }
            val seen = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("\"v1\"", seen.getHeader("If-None-Match"))
        } finally {
            server.shutdown()
        }
    }
}
