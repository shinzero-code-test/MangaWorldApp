package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.StellarDecryptInterceptor
import com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
import com.exapps.mangaworld.core.data.remote.scraper.hijalaOwnsChapter
import com.exapps.mangaworld.core.data.remote.scraper.sniffImageMime
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stellar CDN interceptor: host-gated transparent decryption. Non-matching traffic
 * passes through untouched; missing keys and bad tags fail open to the original
 * bytes (Coil renders its error state) — nothing here can crash image loading.
 * Pure OkHttp + javax.crypto: fully JVM-testable, no Android, no Coil internals.
 */
class StellarDecryptInterceptorTest {

    private val chapter = "https://stellarsaber.pro/chapter/kengan-ashura-x/"
    private val binUrl = "https://cdn-stellarsaber.com/stellar-cdn/abc123.bin"
    private val key = ByteArray(16) { (it * 3).toByte() }

    private fun webpBytes(): ByteArray =
        "RIFF....WEBP".toByteArray(Charsets.UTF_8) + ByteArray(64) { it.toByte() }

    private fun encryptedBin(plain: ByteArray): ByteArray {
        val iv = ByteArray(12) { 9 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun request(url: String = binUrl, referer: String? = chapter): Request =
        Request.Builder().url(url).apply {
            if (referer != null) header("Referer", referer)
            header("User-Agent", "t")
        }.build()

    private fun responseFor(request: Request, bytes: ByteArray, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "err")
            .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
            .build()

    private fun chainReturning(response: Response, request: Request): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } returns response
        return chain
    }

    @Test
    fun decryptsMatchingTraffic() {
        val plain = webpBytes()
        val req = request()
        val store = StellarKeyStore().also { it.put(chapter, key) }
        val out = StellarDecryptInterceptor(store).intercept(chainReturning(responseFor(req, encryptedBin(plain)), req))
        assertArrayEquals(plain, out.body!!.bytes())
        assertEquals("image/webp", out.body!!.contentType().toString())
    }

    @Test
    fun nonMatchingTrafficPassesThroughUntouched() {
        val plain = webpBytes()
        val req = request(url = "https://ar.kenmanga.com/wp-content/uploads/cover.webp")
        val resp = responseFor(req, plain)
        val store = StellarKeyStore().also { it.put(chapter, key) }
        assertSame(resp, StellarDecryptInterceptor(store).intercept(chainReturning(resp, req)))
    }

    @Test
    fun missingKeyPassesOriginalBytesThrough() {
        val bin = encryptedBin(webpBytes())
        val req = request()
        val out = StellarDecryptInterceptor(StellarKeyStore())
            .intercept(chainReturning(responseFor(req, bin), req))
        assertArrayEquals(bin, out.body!!.bytes())
    }

    @Test
    fun tamperedPayloadPassesThroughWithoutCrash() {
        val bin = encryptedBin(webpBytes()).also { it[30] = (it[30].toInt() xor 1).toByte() }
        val req = request()
        val store = StellarKeyStore().also { it.put(chapter, key) }
        val out = StellarDecryptInterceptor(store).intercept(chainReturning(responseFor(req, bin), req))
        // Original (still encrypted) bytes flow on; Coil renders its error state.
        assertArrayEquals(bin, out.body!!.bytes())
    }

    @Test
    fun missingRefererSkipsDecryption() {
        val bin = encryptedBin(webpBytes())
        val req = request(referer = null)
        val store = StellarKeyStore().also { it.put(chapter, key) }
        val out = StellarDecryptInterceptor(store).intercept(chainReturning(responseFor(req, bin), req))
        assertArrayEquals(bin, out.body!!.bytes())
    }

    @Test
    fun sniffMime() {
        assertEquals("image/webp", sniffImageMime(webpBytes()))
        assertEquals("image/jpeg", sniffImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)))
        assertEquals(
            "image/png",
            sniffImageMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4))
        )
        assertNull(sniffImageMime(byteArrayOf(1, 2, 3)))
    }
}

/**
 * Hijala chapter-ownership rule (audit 2026-09-24): opaque chapter URLs must belong
 * to the series slug; cross-series rows are rejected, healthy lists unaffected.
 */
class HijalaOwnershipTest {

    @Test
    fun ownChaptersAccepted() {
        assertTrue(hijalaOwnsChapter("lookism", "https://hijala.com/lookism-625/"))
        assertTrue(hijalaOwnsChapter("lookism", "https://hijala.com/lookism-625"))
        assertTrue(hijalaOwnsChapter("wind-breaker", "https://hijala.com/wind-breaker-556-5/"))
    }

    @Test
    fun foreignChaptersRejected() {
        assertTrue(
            !hijalaOwnsChapter(
                "reveries-of-the-moonlight",
                "https://hijala.com/top-1-fighting-tutoring-01-2/"
            )
        )
        assertTrue(!hijalaOwnsChapter("lookism", "https://hijala.com/kokuma-musou-8/"))
    }
}
