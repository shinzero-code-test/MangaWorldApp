package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Scraper for procomic.pro — Custom Next.js/React SPA.
 *
 * This site uses a proprietary REST API for data:
 *   GET /api/public/series/search?status=approved&limit=18&page={page}
 *
 * Chapter images use CDN with signed URLs.
 * Cloudflare Turnstile protection may block API calls.
 *
 * We scrape the SSR HTML from /updates and /series pages as fallback,
 * and use the public API where possible.
 */
class ProComicScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.PROCOMIC, settingsRepo) {

    private val apiBase = "${source.baseUrl}/api/public/series/search"

    private suspend fun apiGet(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = getCookiesForDomain(url)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "${source.baseUrl}/")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (body.isBlank() || !body.trimStart().startsWith("{")) null
            else JSONObject(body)
        }.getOrNull()
    }

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        // Use the public API to get latest series
        val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=20&page=1&sort=latest")
        val items = parseApiResults(json)

        HomeData(
            featured = items.take(8),
            latestChapters = emptyList(),
            trending = items
        )
    }

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        // Navigate to series page and parse SSR content
        val doc = fetchDocument("${source.baseUrl}/series/manga/$slug/$slug")

        val title = doc.selectFirst("h1, .font-bold")?.text()?.cleanText() ?: slug
        val description = doc.selectFirst("p, .text-sm")?.text()?.cleanText() ?: ""

        MangaDetail(
            id = "procomic_$slug",
            slug = slug,
            title = title,
            coverUrl = "",
            source = source,
            description = description,
            url = "${source.baseUrl}/series/manga/$slug/$slug"
        )
    }

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl)
        doc.select("img[src]").mapNotNull { img ->
            val src = img.attr("abs:src").ifEmpty { img.attr("src") }.encodeForUrl()
            src.takeIf { it.isNotBlank() }
        }.filter { it.contains("cdn") || it.contains("wp-content") || it.contains("uploads") }
            .mapIndexed { index, src ->
                ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
            }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=18&page=$page&search=$encoded&sort=latest")
        parseApiResults(json)
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/series?page=$page")
        parseMangaGridFromHtml(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=30&page=1&sort=popular")
        parseApiResults(json)
    }

    override suspend fun browseManga(
        page: Int, genre: String?, status: MangaStatus?, type: MangaType?, sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val sort = when (sortBy) {
            SortBy.POPULARITY -> "popular"
            SortBy.RATING -> "total_popularity"
            SortBy.OLDEST -> "oldest"
            SortBy.LATEST -> "latest"
        }
        val params = mutableListOf("status=approved", "limit=18", "page=$page", "sort=$sort")
        genre?.takeIf { it.isNotBlank() }?.let { params += "search=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val json = apiGet("${source.baseUrl}/api/public/series/search?${params.joinToString("&")}")
        parseApiResults(json)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/series")
        doc.select("label, .text-sm span").map { it.text().cleanText() }
            .filter { it.length in 2..20 }
            .distinct()
    }

    private fun parseApiResults(json: JSONObject?): List<MangaItem> {
        val data = json?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val obj = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = obj.optString("slug", "")
            if (slug.isBlank()) return@mapNotNull null
            val title = obj.optString("title", slug)
            val metadata = obj.optJSONObject("metadata")
            MangaItem(
                id = "procomic_$slug",
                slug = slug,
                title = title.cleanText(),
                coverUrl = obj.optString("thumbnail", "").encodeForUrl(),
                source = source,
                genres = metadata?.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                status = MangaStatus.from(obj.optString("status")),
                type = MangaType.from(metadata?.optString("type")),
                url = "${source.baseUrl}/series/${obj.optString("type", "manga")}/${obj.optString("id")}/$slug"
            )
        }
    }

    private fun parseMangaGridFromHtml(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select("a[href*='/series/']").mapNotNull { a ->
            val href = a.attr("abs:href").ifEmpty { a.attr("href").absoluteUrl() }
            val parts = href.substringAfter("/series/").trimEnd('/').split("/")
            val slug = parts.lastOrNull() ?: return@mapNotNull null
            if (slug.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3")?.text()?.cleanText() ?: slug
            MangaItem(
                id = "procomic_$slug", slug = slug, title = title,
                coverUrl = img?.attr("abs:src")?.encodeForUrl().orEmpty(),
                source = source, url = href
            )
        }.distinctBy { it.id }
    }
}
