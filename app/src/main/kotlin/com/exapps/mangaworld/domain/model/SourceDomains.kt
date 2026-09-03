package com.exapps.mangaworld.domain.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime base-URL overrides for online sources.
 *
 * Source domains move (e.g. manga-starz.net → starzmanga.com). Shipping an app
 * update for every move is slow, so each source id has a Remote Config key
 * `source_<id>_base_url`. Valid overrides win over the enum default;
 * blank/invalid values are ignored so a bad remote value can never break all
 * networking for a source.
 *
 * Lives in the domain layer (pure JVM, no Android deps) so every layer can
 * resolve URLs without new imports — `domain.model.*` is already imported
 * everywhere. Read path is a lock-free map lookup, safe from scraper IO
 * threads and the Coil interceptor.
 */
object SourceDomainOverrides {

    private val overrides = ConcurrentHashMap<String, String>()

    /** Replace the whole override table (Remote Config apply path). */
    fun replaceAll(map: Map<String, String>) {
        overrides.clear()
        map.forEach { (id, url) ->
            normalizeBaseUrl(url)?.let { overrides[id] = it }
        }
    }

    /** Effective base URL for a source id: valid override, else enum default. */
    fun baseUrlFor(sourceId: String, default: String): String =
        overrides[sourceId] ?: default

    fun snapshot(): Map<String, String> = overrides.toMap()

    companion object {
        /**
         * Returns a canonical base URL or null when the remote value is not a
         * plain https? origin (path/query/whitespace → rejected).
         */
        fun normalizeBaseUrl(raw: String?): String? {
            val trimmed = raw?.trim()?.trimEnd('/')?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val match = Regex("^https?://[A-Za-z0-9.-]+(?::\\d+)?$").matchEntire(trimmed) ?: return null
            return match.value.lowercase()
        }
    }
}

/**
 * Effective origin for this source: Remote Config override wins, enum default
 * otherwise. Always build entry-point URLs, Referers, Jsoup base URIs,
 * WebView-solver targets and trust checks from this — never the raw enum
 * `baseUrl` (which cannot follow domain moves).
 */
fun MangaSource.effectiveBaseUrl(): String =
    SourceDomainOverrides.baseUrlFor(id, baseUrl)

/** Host of the effective base URL (cookie/WebView/interceptor matching). */
fun MangaSource.effectiveHost(): String =
    effectiveBaseUrl().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
