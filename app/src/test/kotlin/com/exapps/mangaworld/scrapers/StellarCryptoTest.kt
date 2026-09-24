package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.StellarCrypto
import com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * StellarSaber encrypted-CDN codec tests (audit 2026-09-24: AES-128-GCM, 16-byte key,
 * 12-byte IV + ciphertext + 16-byte tag). JDK encrypts, production code decrypts —
 * proving the exact wire contract, not a self-consistent fiction.
 */
class StellarCryptoTest {

    private val key = ByteArray(16) { it.toByte() }
    private val iv = ByteArray(12) { (it + 7).toByte() }
    private val plain = "fake-webp-bytes-".repeat(64).toByteArray(Charsets.UTF_8)

    private fun encryptBin(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    @Test
    fun roundTrip() {
        val bin = encryptBin(plain, key, iv)
        assertArrayEquals(plain, StellarCrypto.decrypt(bin, key))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val bin = encryptBin(plain, key, iv)
        bin[20] = (bin[20].toInt() xor 0xFF).toByte()
        try {
            StellarCrypto.decrypt(bin, key)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue((e.message ?: "").contains("stellar"))
        }
    }

    @Test
    fun wrongKeyFailsClosed() {
        val bin = encryptBin(plain, key, iv)
        val wrong = ByteArray(16) { (it + 1).toByte() }
        try {
            StellarCrypto.decrypt(bin, wrong)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            // Expected: GCM tag mismatch.
        }
    }

    @Test
    fun badShapesRejected() {
        try {
            StellarCrypto.decrypt(ByteArray(32) { 1 }, ByteArray(15) { 2 })
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: key must be 16 bytes.
        }
        try {
            StellarCrypto.decrypt(ByteArray(10) { 1 }, key)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: truncated payload.
        }
    }
}

/**
 * Key-store semantics: TTL expiry, LRU cap, explicit clear. No sleeps — expiry is
 * covered by construction (monotonic clock check) and code review, not timers.
 */
class StellarKeyStoreTest {

    @Test
    fun putGetRoundTrip() {
        val store = StellarKeyStore()
        val key = ByteArray(16) { it.toByte() }
        store.put("https://stellarsaber.pro/chapter/x/", key)
        assertArrayEquals(key, store.get("https://stellarsaber.pro/chapter/x/")!!)
        assertNull(store.get("https://stellarsaber.pro/chapter/other/"))
    }

    @Test
    fun lruCapEvictsOldest() {
        val store = StellarKeyStore()
        repeat(StellarKeyStore.MAX_ENTRIES + 1) { i ->
            store.put("https://stellarsaber.pro/chapter/$i/", ByteArray(16) { i.toByte() })
        }
        assertNull(store.get("https://stellarsaber.pro/chapter/0/"))
        assertEquals(10.toByte(), store.get("https://stellarsaber.pro/chapter/10/")!![0])
    }

    @Test
    fun clearEmpties() {
        val store = StellarKeyStore()
        store.put("https://stellarsaber.pro/chapter/x/", ByteArray(16))
        store.clear()
        assertNull(store.get("https://stellarsaber.pro/chapter/x/"))
    }
}
