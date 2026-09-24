package com.exapps.mangaworld.core.data.remote.scraper

import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * StellarSaber encrypted-CDN codec (live-verified 2026-09-24, `tmp/analysis/16-*`).
 *
 * Each `.bin` object is **AES-128-GCM** (NOT AES-256): a 12-byte IV followed by
 * ciphertext plus the trailing 16-byte auth tag, decrypted with the per-chapter
 * 16-byte key from `flavor_cdn_get_key`. Pure JVM-safe (`javax.crypto`).
 *
 * GCM authentication is load-bearing: a wrong/stale key fails closed with an
 * [IOException], never a corrupt image.
 */
object StellarCrypto {

    const val KEY_BYTES = 16
    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    fun decrypt(bin: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Stellar key must be $KEY_BYTES bytes (AES-128)" }
        require(bin.size > IV_BYTES + GCM_TAG_BITS / 8) { "truncated Stellar payload" }
        val iv = bin.copyOfRange(0, IV_BYTES)
        val ct = bin.copyOfRange(IV_BYTES, bin.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw IOException("stellar decrypt failed", e)
        }
    }
}
