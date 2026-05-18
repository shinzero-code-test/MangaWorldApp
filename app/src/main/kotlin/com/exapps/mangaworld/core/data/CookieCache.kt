package com.exapps.mangaworld.core.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple thread-safe in-memory cookie store used by the Coil image-loading
 * OkHttpClient interceptor. Cookies are populated whenever the user solves
 * a Cloudflare challenge, so that image requests to CF-protected domains
 * (e.g. starz.manga-starz.net) automatically include the cf_clearance token.
 */
object CookieCache {

    private val store = ConcurrentHashMap<String, String>()

    fun put(domain: String, cookies: String) {
        store[domain.lowercase()] = cookies
    }

    fun get(domain: String): String? {
        val lower = domain.lowercase()
        // Exact match first
        store[lower]?.let { return it }
        // Then walk up subdomain levels:
        //   "starz.manga-starz.net" → "manga-starz.net"
        var idx = lower.indexOf('.')
        while (idx >= 0 && idx < lower.length - 1) {
            val parent = lower.substring(idx + 1)
            store[parent]?.let { return it }
            idx = lower.indexOf('.', idx + 1)
        }
        return null
    }

    fun clear() = store.clear()
}
