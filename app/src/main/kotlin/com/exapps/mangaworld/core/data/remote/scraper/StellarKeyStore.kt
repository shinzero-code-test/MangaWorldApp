package com.exapps.mangaworld.core.data.remote.scraper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived store for StellarSaber per-chapter decryption keys.
 *
 * Keys are volatile by design (report: `cdnNonce` + key must never be reused across
 * chapters or cached long). Entries expire after [TTL_MS] and the map is LRU-capped,
 * so a stale key can at worst fail one chapter's images (GCM fails closed anyway).
 * Keyed by chapter URL — the Coil fetcher recovers it from the image Referer header.
 */
@Singleton
class StellarKeyStore @Inject constructor() {

    private data class Entry(val key: ByteArray, val storedAt: Long)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun put(chapterUrl: String, key: ByteArray) {
        require(key.size == StellarCrypto.KEY_BYTES) { "bad Stellar key length" }
        map[chapterUrl] = Entry(key.copyOf(), System.currentTimeMillis())
        while (map.size > MAX_ENTRIES) {
            map.entries.iterator().let { it.next(); it.remove() }
        }
    }

    @Synchronized
    fun get(chapterUrl: String): ByteArray? {
        val entry = map[chapterUrl] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            map.remove(chapterUrl)
            return null
        }
        return entry.key.copyOf()
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    companion object {
        const val TTL_MS = 60 * 60 * 1000L
        const val MAX_ENTRIES = 10
    }
}
