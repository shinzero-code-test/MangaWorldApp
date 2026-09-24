package com.exapps.mangaworld.core.coil

import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.exapps.mangaworld.core.data.remote.scraper.StellarCrypto
import com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Request
import okio.Buffer
import okio.FileSystem
import java.io.IOException

/**
 * Coil fetcher for StellarSaber's encrypted CDN (`.bin` objects on
 * `cdn-stellarsaber.com`). Fetches the encrypted bytes, decrypts AES-128-GCM with
 * the per-chapter key (recovered from the image Referer = chapter URL), and hands
 * Coil plain image bytes — so Coil's memory/disk cache, sampling and transforms all
 * work unchanged.
 *
 * Registered FIRST in [MangaWorldApp.newImageLoader], ahead of Coil's default HTTP
 * fetcher (user components take precedence). Matches `.bin` URLs only; everything
 * else falls through untouched. Failures surface as errors (never crashes, never
 * corrupt images — GCM authentication fails closed).
 */
class StellarCdnFetcher(
    private val data: String,
    // Headers (not Coil Options) so the fetch path stays JVM-unit-testable.
    private val headers: Headers,
    private val callFactory: Call.Factory,
    private val keys: StellarKeyStore
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // ChapterPage headers always carry Referer: <chapterUrl> for this source.
        val chapterUrl = headers["Referer"]
            ?: throw IOException("stellar: missing chapter Referer")
        val key = keys.get(chapterUrl)
            ?: throw IOException("stellar: no decryption key for chapter")

        val request = Request.Builder()
            .url(data)
            .headers(headers)
            .get()
            .build()
        val response = callFactory.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("stellar: HTTP ${it.code}")
            val bin = it.body?.source()?.readByteArray()
                ?: throw IOException("stellar: empty body")
            val plain = try {
                StellarCrypto.decrypt(bin, key)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("stellar: decrypt failed", e)
            }
            return SourceResult(
                source = Buffer().write(plain),
                fileSystem = FileSystem.SYSTEM,
                mimeType = sniffMime(plain)
            )
        }
    }

    class Factory(
        private val callFactory: Call.Factory,
        private val keys: StellarKeyStore
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.contains("cdn-stellarsaber.com") || !data.endsWith(".bin")) return null
            return StellarCdnFetcher(data, options.headers, callFactory, keys)
        }
    }

    companion object {
        /** Content sniffing: never trust the `.bin` suffix (or any extension). */
        fun sniffMime(bytes: ByteArray): String? = when {
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
    }
}
