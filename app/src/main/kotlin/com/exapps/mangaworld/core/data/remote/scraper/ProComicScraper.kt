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
        if (body.isBlank() || !body.trimStart().startsWith("{")) return@withContext null
        val json = JSONObject(body)
        // Detect Turnstile / Cloudflare blocking — propagate to trigger WebView solver
        val message = json.optString("message", "")
        if (message.contains("Turnstile", ignoreCase = true) ||
            message.contains("cloudflare", ignoreCase = true)) {
            throw CloudflareChallengeException(
                source.baseUrl.removePrefix("https://").removePrefix("http://"),
                url
            )
        }
        json
    }

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        // Try API first, fall back to HTML scraping
        val items = try {
            val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=20&page=1&sort=latest")
            parseApiResults(json)
        } catch (_: Exception) {
            // API blocked by Turnstile — scrape SSR HTML instead
            val doc = fetchDocument("${source.baseUrl}/series")
            parseMangaGridFromHtml(doc)
        }

        HomeData(
            featured = items.distinctBy { it.id }.take(8),
            latestChapters = emptyList(),
            trending = items.distinctBy { it.id }
        )
    }

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        // Try API first, fall back to HTML scraping
        val matchedItem = try {
            val encoded = java.net.URLEncoder.encode(slug.replace("-", " "), "UTF-8")
            val searchJson = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=50&page=1&sort=latest&search=$encoded")
            val allItems = parseApiResults(searchJson)
            allItems.find { it.slug == slug || it.url.endsWith("/$slug") }
        } catch (_: Exception) { null }

        if (matchedItem != null) {
            // Extract type and id from URL: /series/{type}/{id}/{slug}
            val pathAfterSeries = matchedItem.url.substringAfter("/series/")
            val parts = pathAfterSeries.trimEnd('/').split("/")
            val seriesType = parts.getOrElse(0) { "manga" }
            val seriesId = parts.getOrElse(1) { "" }

            // Get full detail from the series API (includes chapters)
            val detailJson = try {
                apiGet("${source.baseUrl}/api/public/series/$seriesType/$seriesId/$slug")
            } catch (_: Exception) { null }
            val description = detailJson?.optString("description", "") ?: ""
            val cdnPath = detailJson?.optString("cdn_path", "cdn3") ?: "cdn3"

            // Parse chapters from the full detail response
            val chaptersJson = detailJson?.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until chaptersJson.length()).mapNotNull { i ->
                val ch = chaptersJson.optJSONObject(i) ?: return@mapNotNull null
                val chNum = ch.optString("chapter_number", "").toFloatOrNull() ?: return@mapNotNull null
                val chId = ch.optInt("id", 0)
                val chMeta = ch.optJSONObject("metadata")
                val images = chMeta?.optJSONArray("images") ?: JSONArray()

                // Build chapter page URL — use chapter ID for uniqueness
                val chapterUrl = if (chId > 0) {
                    "${source.baseUrl}/series/$seriesType/$seriesId/$slug/$chId"
                } else {
                    "${source.baseUrl}/series/$seriesType/$seriesId/$slug/$chNum"
                }

                Chapter(
                    id = "${slug}_${chId}_${chNum}",
                    mangaId = matchedItem.id,
                    number = chNum,
                    title = ch.optString("title").ifBlank { null },
                    url = chapterUrl,
                    // Store image count in totalPages for display
                    totalPages = images.length()
                )
            }.distinctBy { it.id }.sortedByDescending { it.number }

            MangaDetail(
                id = matchedItem.id,
                slug = matchedItem.slug,
                title = matchedItem.title,
                coverUrl = matchedItem.coverUrl,
                source = source,
                description = description,
                genres = matchedItem.genres,
                status = matchedItem.status,
                type = matchedItem.type,
                totalChapters = chapters.size,
                chapters = chapters,
                url = matchedItem.url
            )
        } else {
            // Fallback: try the series page directly
            MangaDetail(
                id = "procomic_$slug",
                slug = slug,
                title = slug.replace("-", " ").replaceFirstChar { it.uppercase() },
                coverUrl = "",
                source = source,
                url = "${source.baseUrl}/series/manga/$slug/$slug"
            )
        }
    }

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        // Try to extract series info from URL: /series/{type}/{id}/{slug}/{chapterNumber}
        val pathAfterSeries = chapterUrl.substringAfter("/series/").substringBefore("?")
        val parts = pathAfterSeries.trimEnd('/').split("/")

        var cdnImages: List<String> = emptyList()
        if (parts.size >= 4) {
            val seriesType = parts[0]
            val seriesId = parts[1]
            val seriesSlug = parts[2]
            val chapterNum = parts[3]

            // Try API first
            try {
                val detailJson = apiGet("${source.baseUrl}/api/public/series/$seriesType/$seriesId/$seriesSlug")
                val cdnPath = detailJson?.optString("cdn_path", "cdn3") ?: "cdn3"
                val chaptersJson = detailJson?.optJSONArray("chapters") ?: JSONArray()

                val matchingChapter = (0 until chaptersJson.length()).mapNotNull { i ->
                    chaptersJson.optJSONObject(i)
                }.firstOrNull { ch ->
                    ch.optString("chapter_number", "") == chapterNum
                }

                if (matchingChapter != null) {
                    val chMeta = matchingChapter.optJSONObject("metadata")
                    val images = chMeta?.optJSONArray("images") ?: JSONArray()
                    cdnImages = (0 until images.length()).mapNotNull { i ->
                        val imgPath = images.optString(i)
                        if (imgPath.isNotBlank()) "https://$cdnPath.procomic.pro$imgPath" else null
                    }
                }
            } catch (_: Exception) { }
        }

        if (cdnImages.isNotEmpty()) {
            cdnImages.mapIndexed { index, src ->
                ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
            }
        } else {
            // Fallback: scrape chapter page HTML
            // Procomic SSR embeds image URLs in page content, not always as <img> tags
            val doc = fetchDocument(chapterUrl)
            val html = doc.outerHtml()
            
            // Extract chapter image URLs from the raw HTML
            // Procomic embeds URLs as string literals in JS without https:// prefix
            // Pattern: procomic.pro/chapters/{seriesId}/{chapterId}/p{page}/{hash}-desktop.avif
            val imgUrls = Regex("(?:https?://)?(?:app\\.)?procomic\\.(?:pro|net)/chapters/[^\\s\"<>]+?desktop\\.avif")
                .findAll(html).map { match ->
                    val url = match.value
                    if (url.startsWith("http")) url else "https://$url"
                }.distinct().toList()
            
            if (imgUrls.isNotEmpty()) {
                imgUrls.mapIndexed { index, src ->
                    ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
                }
            } else {
                // Last resort: try <img> tags
                doc.select("img[alt^='page']")
                    .mapNotNull { img ->
                        val src = img.attr("src")
                        if (src.isNotBlank() && !src.startsWith("data:")) src else null
                    }
                    .filter { it.contains(".jpg") || it.contains(".png") || it.contains(".webp") || it.contains(".avif") }
                    .distinct()
                    .mapIndexed { index, src ->
                        ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
                    }
            }
        }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        // Try API first, fall back to HTML scraping
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=18&page=$page&search=$encoded&sort=latest")
            val items = parseApiResults(json)
            if (items.isNotEmpty()) return@runCatching items.distinctBy { it.id }
        } catch (_: Exception) { }
        // Fallback: HTML scraping
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = fetchDocument("${source.baseUrl}/series?search=$encoded")
        parseMangaGridFromHtml(doc)
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(genre, "UTF-8")
        val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=18&page=$page&genre=$encoded&sort=latest")
        parseApiResults(json)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        try {
            val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=30&page=1&sort=popular")
            val items = parseApiResults(json)
            if (items.isNotEmpty()) return@runCatching items.distinctBy { it.id }
        } catch (_: Exception) { }
        val doc = fetchDocument("${source.baseUrl}/series?sort=popular")
        parseMangaGridFromHtml(doc)
    }

    override suspend fun browseManga(
        page: Int, genre: String?, status: MangaStatus?, type: MangaType?, sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        // Try API first, fall back to HTML scraping
        try {
            val sort = when (sortBy) {
                SortBy.POPULARITY -> "popular"
                SortBy.RATING -> "total_popularity"
                SortBy.OLDEST -> "oldest"
                SortBy.LATEST -> "latest"
            }
            val params = mutableListOf("status=approved", "limit=18", "page=$page", "sort=$sort")
            genre?.takeIf { it.isNotBlank() }?.let { params += "search=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            val json = apiGet("${source.baseUrl}/api/public/series/search?${params.joinToString("&")}")
            val items = parseApiResults(json)
            if (items.isNotEmpty()) return@runCatching items.distinctBy { it.id }
        } catch (_: Exception) { }
        // Fallback: HTML scraping
        val doc = fetchDocument("${source.baseUrl}/series")
        parseMangaGridFromHtml(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val json = apiGet("${source.baseUrl}/api/public/series/search?status=approved&limit=1&page=1&sort=latest")
        val data = json?.optJSONObject("metadata")?.optJSONArray("genres")
            ?: json?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("metadata")?.optJSONArray("genres")
        if (data != null) {
            (0 until data.length()).mapNotNull { i -> data.optString(i).ifBlank { null } }.distinct()
        } else {
            // Fallback: try to get from HTML
            val doc = fetchDocument("${source.baseUrl}/series")
            doc.select("label, .text-sm span").map { it.text().cleanText() }
                .filter { it.length in 2..20 }
                .distinct()
        }
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
        return doc.select("a[href^='/series/']").mapNotNull { a ->
            val href = a.attr("abs:href").ifEmpty { a.attr("href").absoluteUrl() }
            val parts = href.substringAfter("/series/").trimEnd('/').split("/")
            if (parts.size < 3) return@mapNotNull null
            val seriesType = parts[0]
            val seriesId = parts[1]
            val slug = parts[2]
            if (slug.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3")?.text()?.cleanText() ?: slug
            // Extract cover URL from img src (app.procomic.pro/series-cards/...)
            val coverUrl = img?.attr("src")?.takeIf { it.isNotBlank() } ?: ""
            MangaItem(
                id = "procomic_$slug", slug = slug, title = title,
                coverUrl = coverUrl.encodeForUrl(),
                source = source, url = href
            )
        }.distinctBy { it.id }
    }
}
