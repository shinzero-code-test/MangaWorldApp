package com.exapps.mangaworld.core.data.remote.scraper

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transparent decryption for StellarSaber's encrypted CDN (audit 2026-09-24).
 *
 * `.bin` objects on `cdn-stellarsaber.com` are AES-128-GCM (12-byte IV + ciphertext +
 * 16-byte tag) under a per-chapter key. This interceptor swaps the encrypted body for
 * plain image bytes *before* Coil sees the response, so the entire Coil pipeline
 * (sampling, transforms, memory/disk cache) works unchanged — no custom Fetcher, no
 * Coil-internal APIs.
 *
 * Scope discipline: matches `.bin` on the Stellar CDN host ONLY; every other request
 * passes through untouched (same cost as any other interceptor). Missing keys,
 * failed auth tags, or non-2xx responses pass the original bytes through — Coil then
 * shows its normal error placeholder. Nothing here can crash image loading.
 *
 * Must be registered on every OkHttp client Coil may use (shared + image clients).
 */
@Singleton
class StellarDecryptInterceptor @Inject constructor(
    private val keys: StellarKeyStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != CDN_HOST || !url.encodedPath.endsWith(".bin")) {
            return chain.proceed(request)
        }
        val response = chain.proceed(request)
        // ChapterPage headers always carry Referer: <chapterUrl> for this source.
        val key = request.header("Referer")?.let { keys.get(it) } ?: return response
        if (!response.isSuccessful) return response
        // Consumed exactly once: both branches below rebuild a fresh body, because a
        // re-read of the drained original throws (this bit the fail-open path once).
        val contentType = response.header("Content-Type")?.let {
            runCatching { it.toMediaTypeOrNull() }.getOrNull()
        }
        val bin = runCatching { response.body?.bytes() }.getOrNull() ?: return response
        val plain = try {
            StellarCrypto.decrypt(bin, key)
        } catch (_: Exception) {
            null
        }
        return if (plain != null) {
            response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .removeHeader("Transfer-Encoding")
                .body(plain.toResponseBody(sniffImageMime(plain)?.toMediaTypeOrNull()))
                .build()
        } else {
            // Wrong/stale key or corrupt payload: GCM fails closed. Hand the ORIGINAL
            // bytes on in a fresh body so Coil renders its error state instead of
            // crashing the load on a drained stream.
            response.newBuilder()
                .body(bin.toResponseBody(contentType))
                .build()
        }
    }

    companion object {
        const val CDN_HOST = "cdn-stellarsaber.com"
    }
}

/**
 * Content sniffing: never trust the `.bin` suffix (or any extension).
 * Internal for unit tests.
 */
internal fun sniffImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() -> "image/gif"
    else -> null
}
