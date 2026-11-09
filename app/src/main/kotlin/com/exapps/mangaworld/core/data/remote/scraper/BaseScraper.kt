package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.core.data.resolveCookieForDomain
import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.firebase.RemoteSelectorOverridesStore
import com.exapps.mangaworld.core.util.suspendRunCatching
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

interface MangaScraper {
    /** Stable source id (== stored sourceId, descriptor id, registry key). */
    val sourceId: String

    /**
     * Phase 2A migration hook: the verified manifest driving this scraper, when the
     * plugin was installed from signed JSON (pilots, then remote descriptors in 2B).
     * Null for classic builtins whose metadata still comes from code. The plugin
     * wrapper ([SourcePlugin.descriptor]) is authoritative — this is the scraper-side
     * view for engine code that only holds a [MangaScraper].
     */
    val pluginDescriptor: com.exapps.mangaworld.core.source.plugins.PluginManifest?
        get() = null

    suspend fun getHomeData(): Result<HomeData>
    suspend fun getMangaDetail(slug: String): Result<MangaDetail>
    suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>>
    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaItem>>
    suspend fun getMangaByGenre(genre: String, page: Int = 1): Result<List<MangaItem>>
    suspend fun browseManga(
        page: Int = 1,
        genre: String? = null,
        status: MangaStatus? = null,
        type: MangaType? = null,
        sortBy: SortBy = SortBy.LATEST
    ): Result<List<MangaItem>> = when {
        genre != null -> getMangaByGenre(genre, page)
        page == 1 -> getPopularManga()
        else -> Result.success(emptyList())
    }
    suspend fun getPopularManga(): Result<List<MangaItem>>
    suspend fun getGenres(): Result<List<String>>
}

class CloudflareChallengeException(
    val domain: String,
    val targetUrl: String,
    message: String = "Cloudflare challenge required for $domain"
) : RuntimeException(message)

/**
 * Server-gated chapter (coin lock, shortlink wall, `status:"locked"` chapter payload).
 * Stable token message, translated at render time like download error codes — readers
 * must stop at the lock UI, never attempt purchase/unlock flows.
 */
class ChapterLockedException : RuntimeException("locked")

/** Non-Cloudflare HTTP failure from a source (4xx/5xx) — surfaced instead of parsed-as-content. */
class SourceHttpException(val code: Int, val url: String) :
    RuntimeException("HTTP $code from $url")

abstract class BaseScraperImpl(
    protected val client: OkHttpClient,
    override val sourceId: String,
    protected val defaultBaseUrl: String,
    protected val settingsRepo: SettingsRepository,
    override val pluginDescriptor: com.exapps.mangaworld.core.source.plugins.PluginManifest? = null
) : MangaScraper {

    /**
     * Effective origin for this source: Remote Config override
     * (`source_<id>_base_url`) wins, code default otherwise. Domains move
     * (starz: manga-starz.net → starzmanga.com) — always build entry-point
     * URLs, Referers and Jsoup base URIs from this, never the raw
     * `defaultBaseUrl` (which cannot follow domain moves).
     */
    protected val resolvedBaseUrl: String
        get() = SourceDomainOverrides.baseUrlFor(sourceId, defaultBaseUrl)

    protected suspend fun fetchDocument(url: String, extraHeaders: Map<String, String> = emptyMap()): Document =
        withContext(Dispatchers.IO) {
            // Strict URI parsing throws on non-ASCII/space characters that are
            // perfectly legal for OkHttp — guard it so scraped Arabic hrefs don't
            // become hard failures (M-review; root amplifier of genre bugs).
            val domain = runCatching { java.net.URI(url).host }
                .getOrNull()
                ?: resolvedBaseUrl.removePrefix("https://").removePrefix("http://")
            val cookies = resolveCookieForUrl(settingsRepo, url)

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Referer", resolvedBaseUrl + "/")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val request = requestBuilder.build()

            val call = client.newCall(request)
            // U-18: coroutine cancellation must abort the blocking call — use
            // the Job's invokeOnCancellation via a launched handler instead of
            // the internal CancellableContinuation overload (kotlinx 1.8+ marks
            // the latter internal: currentCoroutineContext().invokeOnCancellation
            // does not compile from app code).
            currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCancellation { runCatching { call.cancel() } }
            val response = call.execute()
            val body: String
            val code: Int
            try {
                body = response.body?.string() ?: ""
                code = response.code
                val headers = response.headers
                val doc = Jsoup.parse(body, url)
                val title = doc.title().lowercase()
                // Narrowed (v9.5.9, review 2026-11-07): the previous matcher fired on
                // passive embeds that ship on normal pages (RUM jsd/main.js via
                // /cdn-cgi/, Turnstile comment widgets via challenges.cloudflare.com
                // / cf-turnstile, bot cookie __cf_bm, ordinary JS word "orchestrate").
                // A challenge gates content: the cf-mitigated header (strongest), an
                // interstitial title, or the live challenge marker __cf_chl_ that
                // only appears in the interstitial body.
                // U-22: 403/503 is a WEAK signal alone — a non-CF 403 (IP ban,
                // rate-limit) must not route to the solver. It counts only with
                // a CF marker (header/title/body).
                val isCf = isCloudflareChallenge(code, title, body, headers["cf-mitigated"])
                if (isCf) {
                    throw CloudflareChallengeException(resolvedBaseUrl.removePrefix("https://").removePrefix("http://"), url)
                }
                // Server error bodies used to be parsed as content → silent empty results.
                // Fail loudly so repositories can fall back to cache instead.
                if (code >= 400) {
                    throw SourceHttpException(code, url.takeWhile { it != '?' })
                }
                return@withContext doc
            } finally {
                response.close()
            }
        }

    protected fun String.absoluteUrl(base: String = resolvedBaseUrl): String {
        val t = trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "$base$t"
            // Explicit: callers must filter data: URIs before persisting;
            // never absolutize them into a bogus https://host/data:... URL.
            t.startsWith("data:") -> t
            t.isBlank() -> t
            else -> "$base/$t"
        }
    }

    /**
     * Canonical lazy-image preference: data-src/data-lazy-src first, then
     * abs:src, then src — skipping any base64 `data:` placeholder at each
     * step. Returns null when no usable URL exists. Use this from every
     * chapter-page path instead of hand-rolling the order per file.
     */
    protected fun org.jsoup.nodes.Element.preferredImageUrl(): String? {
        val dataSrc = attr("data-src").ifEmpty { attr("data-lazy-src") }.trim()
        if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) return dataSrc
        val absSrc = attr("abs:src").trim()
        if (absSrc.isNotBlank() && !absSrc.startsWith("data:")) return absSrc
        val src = attr("src").trim()
        if (src.isNotBlank() && !src.startsWith("data:")) return src
        return null
    }

    protected fun String.cleanText() = trim().replace("\\s+".toRegex(), " ")

    /**
     * Encodes a URL so it only contains ASCII characters, making it safe to use
     * as an HTTP header value (e.g. Referer). Non-ASCII path/query chars (Arabic,
     * CJK, etc.) are percent-encoded; already-ASCII URLs are returned unchanged.
     */
    protected fun String.encodeForHeader(): String = runCatching {
        val u = java.net.URL(this)
        java.net.URI(u.protocol, u.userInfo, u.host, u.port, u.path, u.query, u.ref)
            .toASCIIString()
    }.getOrDefault(this)

    protected fun String.encodeForUrl(): String = replace(" ", "%20")

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }

    protected suspend fun getCookiesForDomain(url: String): String? = resolveCookieForUrl(settingsRepo, url)

    protected suspend fun buildImageHeaders(imageUrl: String, referer: String): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        val safeReferer = referer.encodeForHeader()
        if (safeReferer.isNotBlank()) headers["Referer"] = safeReferer

        val imageDomain = runCatching { java.net.URI(imageUrl).host }.getOrNull().orEmpty()
        if (imageDomain.isNotBlank()) {
            resolveCookieForDomain(settingsRepo, imageDomain)
                ?.takeIf { it.isNotBlank() }
                ?.let { headers["Cookie"] = it }
        }
        return headers
    }

    protected open fun remoteSelector(key: String, default: String): String =
        RemoteSelectorOverridesStore.selector(sourceId, key, default)

    /**
     * U-24: shared scraper envelope — cancellation-aware (via [suspendRunCatching])
     * + telemetry on failure. All scraper Result paths should build on this.
     * CF challenges are rethrown (callers route to the solver), never logged
     * as generic failures.
     */
    protected suspend fun <T> guarded(stage: String, block: suspend () -> T): Result<T> =
        suspendRunCatching(block).onFailure { e ->
            if (e !is CloudflareChallengeException) ScraperTelemetry.logFailure(sourceId, stage, e)
        }

    /**
     * U-24: probe-loop unification — `runCatching { fetchDocument }.getOrNull()`
     * appeared at 3+ sites with slightly different CF handling. Returns the
     * first document that loads, or null when every probe failed (CF returns
     * null here; callers that must surface CF should use [guarded] instead).
     */
    protected suspend fun probeDocument(url: String): Document? =
        suspendRunCatching { fetchDocument(url) }.getOrNull()

    /** First-success probe across [urls] in order; null when all fail. */
    protected suspend fun probeFirst(urls: List<String>): Document? {
        for (u in urls) {
            val d = probeDocument(u)
            if (d != null) return d
        }
        return null
    }

    /**
     * U-22 helper — single CF heuristic site. `code` alone never triggers;
     * status 403/503 counts only together with a CF marker.
     */
    internal fun isCloudflareChallenge(
        code: Int,
        title: String,
        body: String,
        cfMitigatedHeader: String? = null
    ): Boolean {
        val cfMitigated = cfMitigatedHeader?.lowercase() == "challenge"
        val t = title.lowercase()
        val hasMarker = cfMitigated ||
            t.contains("just a moment") ||
            t.contains("attention required") ||
            t.contains("access denied") ||
            body.contains("__cf_chl_", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true)
        return hasMarker
    }
}
