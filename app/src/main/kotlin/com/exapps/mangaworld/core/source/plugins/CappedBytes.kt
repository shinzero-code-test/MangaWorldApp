package com.exapps.mangaworld.core.source.plugins

import java.io.InputStream

/**
 * Bounded body reader shared by both HTTP fetchers (v9.3.0, audit B8).
 *
 * The previous shape — `body.bytes()` and *then* a size check — buffered the
 * entire response before enforcing the cap. A chunked response has no
 * `Content-Length`, so the pre-check was skipped and an allow-listed host could
 * drive unbounded heap growth into the app process. (Same class as the accepted
 * Q-1 gzip-bomb exposure: the *rejection* worked, the memory spike happened
 * first.)
 *
 * This reads through a fixed buffer and throws as soon as the cap is exceeded,
 * so peak memory is O(cap), independent of what the server sends.
 */
object CappedBytes {

    class TooLarge(val read: Long) : RuntimeException("response exceeds budget ($read bytes)")

    /** Reads up to [maxBytes]; throws [TooLarge] the moment the cap is passed. */
    fun read(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw TooLarge(total)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val DEFAULT_CHUNK = 8 * 1024
}