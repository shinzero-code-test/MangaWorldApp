package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostGateInterceptor
import io.mockk.answers
import io.mockk.capture
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.slot
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C-5: the descriptor-path network interceptor validates every hop against
 * the signed manifest's effective hosts (plus https/userinfo/port
 * discipline), and cookies never cross hosts. Mocked chain — no sockets.
 */
class HostGateInterceptorTest {

    private val allowed = setOf("starzmanga.com", "cdn.starzmanga.com")

    private fun chainFor(url: String, cookie: String? = null): Interceptor.Chain {
        val builder = Request.Builder().url(url).get()
        if (cookie != null) builder.header("Cookie", cookie)
        val request = builder.build()
        val chain: Interceptor.Chain = mockk()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .build()
        }
        return chain
    }

    private fun outgoing(chain: Interceptor.Chain): Request {
        val slot = slot<Request>()
        verify { chain.proceed(capture(slot)) }
        return slot.captured
    }

    @Test
    fun initialHopPassesAndSeedsTag() {
        val chain = chainFor("https://starzmanga.com/manga/", "sess=abc")
        HostGateInterceptor(allowed).intercept(chain)
        val out = outgoing(chain)
        // Same-host cookies stay; the initial host is tagged for later hops.
        assertEquals("sess=abc", out.header("Cookie"))
        assertEquals("starzmanga.com", out.tag(HostGateInterceptor.InitialHost::class.java)?.host)
    }

    @Test
    fun offAllowListHopThrows() {
        try {
            HostGateInterceptor(allowed).intercept(chainFor("https://evil.com/x"))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("evil.com"))
        }
    }

    @Test
    fun userinfoAndPortHopsThrow() {
        try {
            HostGateInterceptor(allowed).intercept(chainFor("https://user@starzmanga.com/x"))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("userinfo"))
        }
        try {
            HostGateInterceptor(allowed).intercept(chainFor("https://starzmanga.com:8443/x"))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("port"))
        }
    }

    @Test
    fun crossHostHopStripsCookies() {
        // A follow-up request retagged from the initial host onto a second
        // allowed host must not carry the first host's cookies.
        val followUp = Request.Builder().url("https://cdn.starzmanga.com/i.png").get()
            .header("Cookie", "sess=abc")
            .tag(
                HostGateInterceptor.InitialHost::class.java,
                HostGateInterceptor.InitialHost("starzmanga.com")
            )
            .build()
        val chain: Interceptor.Chain = mockk()
        every { chain.request() } returns followUp
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .build()
        }
        HostGateInterceptor(allowed).intercept(chain)
        assertNull(outgoing(chain).header("Cookie"))
    }
}
