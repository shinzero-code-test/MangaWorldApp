package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.coil.StellarCdnFetcher
import com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
import com.exapps.mangaworld.core.data.remote.scraper.hijalaOwnsChapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stellar CDN fetcher: factory matching, MIME sniffing, and end-to-end fetch →
 * decrypt through a fake OkHttp [Call]. No Android Context needed (headers-based).
 */
class StellarCdnFetcherTest {

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

    private fun fakeCall(bytes: ByteArray, code: Int = 200): Call {
        val body = bytes.toResponseBody("application/octet-stream".toMediaType())
        val response = Response.Builder()
            .request(Request.Builder().url(binUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "err")
            .body(body)
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns response
        return call
    }

    private fun headers(): Headers = Headers.Builder()
        .add("Referer", chapter)
        .add("User-Agent", "t")
        .build()

    @Test
    fun factoryMatchesOnlyBinUrls() {
        val factory = StellarCdnFetcher.Factory(mockk(relaxed = true), StellarKeyStore())
        assertNotNull(factory.create(binUrl, mockk(relaxed = true), mockk(relaxed = true)))
        assertNull(factory.create("https://cdn-stellarsaber.com/x.webp", mockk(relaxed = true), mockk(relaxed = true)))
        assertNull(factory.create("https://evil.com/x.bin", mockk(relaxed = true), mockk(relaxed = true)))
    }

    @Test
    fun fetchDecrypts() = runTest {
        val plain = webpBytes()
        val store = StellarKeyStore().also { it.put(chapter, key) }
        val fetcher = StellarCdnFetcher(binUrl, headers(), Call.Factory { fakeCall(encryptedBin(plain)) }, store)
        val result = fetcher.fetch() as coil.fetch.SourceResult
        val out = Buffer()
        result.source.readAll(out)
        assertArrayEquals(plain, out.readByteArray())
        assertEquals("image/webp", result.mimeType)
    }

    @Test
    fun fetchWithoutKeyFails() = runTest {
        val fetcher = StellarCdnFetcher(
            binUrl, headers(), Call.Factory { fakeCall(encryptedBin(webpBytes())) }, StellarKeyStore()
        )
        try {
            fetcher.fetch()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue((e.message ?: "").contains("key"))
        }
    }

    @Test
    fun fetchTamperedFailsClosed() = runTest {
        val bin = encryptedBin(webpBytes()).also { it[30] = (it[30].toInt() xor 1).toByte() }
        val store = StellarKeyStore().also { it.put(chapter, key) }
        val fetcher = StellarCdnFetcher(binUrl, headers(), Call.Factory { fakeCall(bin) }, store)
        try {
            fetcher.fetch()
            fail("expected IOException")
        } catch (_: IOException) {
            // Expected: GCM authentication failure, never corrupt bytes.
        }
    }

    @Test
    fun sniffMime() {
        assertEquals("image/webp", StellarCdnFetcher.sniffMime(webpBytes()))
        assertEquals("image/jpeg", StellarCdnFetcher.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)))
        assertEquals(
            "image/png",
            StellarCdnFetcher.sniffMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4))
        )
        assertNull(StellarCdnFetcher.sniffMime(byteArrayOf(1, 2, 3)))
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
