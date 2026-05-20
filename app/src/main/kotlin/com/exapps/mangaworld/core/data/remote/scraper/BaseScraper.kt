package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.core.data.resolveCookieForDomain
import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.firebase.RemoteSelectorOverridesStore
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

interface MangaScraper {
    val source: MangaSource

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

abstract class BaseScraperImpl(
    protected val client: OkHttpClient,
    override val source: MangaSource,
    protected val settingsRepo: SettingsRepository
) : MangaScraper {

    protected suspend fun fetchDocument(url: String, extraHeaders: Map<String, String> = emptyMap()): Document =
        withContext(Dispatchers.IO) {
            val domain = java.net.URI(url).host ?: source.baseUrl.removePrefix("https://").removePrefix("http://")
            val cookies = resolveCookieForUrl(settingsRepo, url)

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Referer", source.baseUrl + "/")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code
            response.close()
            val doc = Jsoup.parse(body, url)
            val title = doc.title().lowercase()
            val isCf = code == 403 ||
                title.contains("just a moment") ||
                title.contains("attention required") ||
                body.contains("cf-chl", ignoreCase = true)
            if (isCf) {
                throw CloudflareChallengeException(source.baseUrl.removePrefix("https://").removePrefix("http://"), url)
            }
            doc
        }

    protected fun String.absoluteUrl(base: String = source.baseUrl): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$base$this"
            else -> "$base/$this"
        }
    }

    protected fun String.toChapterId() = replace("[^a-zA-Z0-9._-]".toRegex(), "_")

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

    protected fun remoteSelector(key: String, default: String): String =
        RemoteSelectorOverridesStore.selector(source.id, key, default)
}
