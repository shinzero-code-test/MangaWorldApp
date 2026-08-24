package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import javax.inject.Inject

/**
 * Custom scraper for ar.kenmanga.com ( AREA Manga / آريا مانجا )
 *
 * This site uses a CUSTOM MangaReader theme variant with different CSS classes:
 *   Cards: div.manga-card > div.card-image > a.card-link-wrap > img
 *          + div.card-info > h3.manga-title > a + div.manga-meta + div.latest-chapter
 *   Detail: div.manga-header-content > div.manga-poster > img + div.manga-title-large
 *           + div.chapters-list (AJAX-loaded)
 *   Search: ?s={query} (standard WP search)
 *   Browse: /browse/
 *   URL: /manga/{slug}/
 */
class AreaScansScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : BaseScraperImpl(client, MangaSource.AREASCANS, settingsRepo) {

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/browse/")
        val mangaCards = parseMangaCards(doc)

        HomeData(
            featured = mangaCards.take(8),
            // Intentional: no verified selector for this site's home chapter
            // cards yet — fabricating one risks broken reader routes. Implement
            // when a fixture from the live site confirms the markup.
            latestChapters = emptyList(),
            trending = mangaCards
        )
    }

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/manga/$slug/"
        val doc = fetchDocument(url)

        val coverUrl = doc.selectFirst(".manga-poster img, .hero-backdrop, .header-bg img")
            ?.let { 
                val style = it.attr("style")
                val bgUrl = Regex("url\\('([^']+)\\'\\)").find(style)?.groupValues?.get(1)
                bgUrl?.ifEmpty { null }
                    ?: it.attr("abs:src").ifEmpty { it.attr("data-src").absoluteUrl() }
            } ?: ""

        val title = doc.selectFirst(".manga-title-large, .manga-title, h1")?.text()?.cleanText() ?: slug

        // Description in .story-text p or .story-section
        val description = doc.selectFirst(".story-text p, .story-text, .manga-synopsis, .manga-description")
            ?.text()?.cleanText() ?: ""

        val statusText = doc.selectFirst(".meta-tag:contains(مستمرة), .meta-tag:contains(مكتملة), .badge.status")
            ?.text()?.cleanText()
        val status = MangaStatus.from(statusText)

        val genres = doc.select(".filter-tag, .meta-tag:not(:contains(مستمرة)):not(:contains(مكتملة))")
            .map { it.text().cleanText() }
            .filter { it.length in 2..20 && !it.contains("فريق") }
            .distinct()

        val bodyText = doc.body().text()
        val type = MangaType.from(
            when {
                bodyText.contains("مانهوا كورية", true) || bodyText.contains("manhwa", true) -> "manhwa"
                bodyText.contains("مانهوا صينية", true) || bodyText.contains("manhua", true) -> "manhua"
                bodyText.contains("مانجا", true) || bodyText.contains("manga", true) -> "manga"
                else -> null
            }
        )

        // Try to get chapters from the chapters-list
        val chapters = doc.select(".chapters-list .chapter-item.ch-item, .chapters-list a.chapter-item, .chapters-list a[href], .chapters-list .chapter-row")
            .mapNotNull { el ->
                val chLink = el.takeIf { el.tagName() == "a" }
                    ?: el.selectFirst("a[href]")
                    ?: return@mapNotNull null
                val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
                // Prefer data-ch attribute (reliable integer)
                val chNum = el.attr("data-ch").toFloatOrNull()
                    ?: ScraperText.firstChapterNumber(chLink.selectFirst(".chap-num")?.text())
                    ?: ScraperText.firstChapterNumber(chLink.text())
                    ?: ScraperText.lastSegmentNumber(chHref)
                    ?: return@mapNotNull null
                val dateText = chLink.selectFirst(".chap-date")?.text()?.cleanText()
                Chapter(
                    id = "${slug}_$chNum",
                    mangaId = "${source.id}_$slug",
                    number = chNum,
                    title = chLink.selectFirst(".chap-num")?.text()?.cleanText()?.replace("الفصل", "")?.trim()?.ifBlank { null }
                        ?: context.getString(com.exapps.mangaworld.R.string.fmt_059, chNum.toInt()),
                    url = chHref
                )
            }.distinctBy { it.url }.sortedByDescending { it.number }

        MangaDetail(
            id = "${source.id}_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            description = description,
            genres = genres,
            tags = genres,
            status = status,
            type = type,
            totalChapters = chapters.size,
            chapters = chapters,
            url = url
        )
    }

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl, extraHeaders = mapOf("Referer" to source.baseUrl + "/"))

        // Strategy 1: Try AJAX endpoint (get_secure_chapter_images) - most reliable
        val chapterId = doc.select("script").mapNotNull { script ->
            Regex("chapterId\\s*=\\s*(\\d+)").find(script.html())?.groupValues?.get(1)
                ?: Regex("ARYA_CHAPTER_ID\\s*=\\s*(\\d+)").find(script.html())?.groupValues?.get(1)
        }.firstOrNull()
            ?: doc.body().classNames().joinToString(" ").let { classes ->
                Regex("postid-(\\d+)").find(classes)?.groupValues?.get(1)
            }

        if (!chapterId.isNullOrBlank()) {
            // AJAX endpoint (get_secure_chapter_images) is the primary and only reliable method.
            // The page is JS-rendered, so HTML parsing (Strategy 2/3) won't find images.
            val formBody = FormBody.Builder()
                .add("action", "get_secure_chapter_images")
                .add("chapter_id", chapterId)
                .build()
            val request = Request.Builder()
                .url("${source.baseUrl}/wp-admin/admin-ajax.php")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", chapterUrl.encodeForHeader())
                .header("X-Requested-With", "XMLHttpRequest")
                .post(formBody)
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val body = response.use { it.body?.string() ?: "{}" }
            val json = org.json.JSONObject(body)
            if (json.optBoolean("success", false)) {
                val html = json.optJSONObject("data")?.optString("content", "") ?: ""
                if (html.isNotBlank()) {
                    val imgDoc = Jsoup.parse(html, source.baseUrl)
                    val ajaxImages = imgDoc.select("img[src]").mapNotNull { img ->
                        val src = img.attr("src")
                        if (src.isNotBlank() && !src.startsWith("data:")) src else null
                    }.distinct()
                    if (ajaxImages.isNotEmpty()) {
                        return@runCatching ajaxImages.mapIndexed { index, src ->
                            ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
                        }
                    }
                }
            }
            // AJAX returned but no images found — don't silently fail, let it fall through
        }

        // Strategy 2: Parse from HTML directly (only works for non-JS-rendered pages)
        val images = doc.select(
            "#reader-canvas img, .reader-container img, .reading-content img, .page-break img"
        )
            .mapNotNull { img ->
                val dataSrc = img.attr("data-src").ifEmpty { null }
                val absSrc = img.attr("abs:src").ifEmpty { null }
                val src = img.attr("src").ifEmpty { null }
                val actualSrc = dataSrc
                    ?: absSrc?.takeIf { !it.startsWith("data:") && !it.contains("readerarea.svg") }
                    ?: src?.takeIf { !it.startsWith("data:") && !it.contains("readerarea.svg") }
                if (actualSrc.isNullOrBlank()) return@mapNotNull null
                val fullSrc = if (actualSrc.startsWith("http")) actualSrc else actualSrc.absoluteUrl()
                fullSrc.encodeForUrl().takeIf {
                    it.isNotBlank() && !it.contains("logo") && !it.contains("avatar") &&
                    !it.contains("loading") && !it.contains("placeholder")
                }
            }
            .filter { it.contains(".jpg") || it.contains(".png") || it.contains(".webp") || it.contains(".gif") || it.contains("wp-content") }
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
            }

        if (images.isNotEmpty()) return@runCatching images

        // Strategy 3: Fallback - extract from ts_reader.run() JSON config if present
        val rawHtml = doc.outerHtml()
        val jsonMatch = Regex("ts_reader\\.run\\((\\{.*?\\})\\)").find(rawHtml)
        if (jsonMatch != null) {
            try {
                val json = org.json.JSONObject(jsonMatch.groupValues[1])
                val sources = json.optJSONArray("sources") ?: org.json.JSONArray()
                val imgArray = sources.optJSONObject(0)?.optJSONArray("images") ?: org.json.JSONArray()
                (0 until imgArray.length()).mapNotNull { i ->
                    val imgSrc = imgArray.optString(i)
                    if (imgSrc.isNotBlank() && (imgSrc.contains(".jpg") || imgSrc.contains(".png") || imgSrc.contains(".webp"))) {
                        ChapterPage(index = i, url = imgSrc.encodeForUrl(), headers = buildImageHeaders(imgSrc, chapterUrl))
                    } else null
                }
            } catch (e: Exception) {
                ScraperTelemetry.logFailure(source.id, "pages_ts_reader", e)
                images
            }
        } else images
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        // Standard WP search page is more reliable than AJAX for this site
        val url = if (page <= 1) "${source.baseUrl}/?s=$encoded" else "${source.baseUrl}/page/$page/?s=$encoded"
        val doc = fetchDocument(url)
        val results = parseMangaCards(doc)

        if (results.isNotEmpty()) return@runCatching results

        // Fallback: try AJAX search (GET with query params — POST body is ignored)
        try {
            val searchUrl = "${source.baseUrl}/wp-admin/admin-ajax.php?action=ts_ac_do_search&ts_ac_query=$encoded"
            // Capture the withContext result — `return@withContext` alone discarded
            // the AJAX hits and search always fell through to HTML results (M-review).
            val ajaxItems: List<MangaItem>? = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", source.baseUrl)
                    .build()
                val response = client.newCall(request).execute()
                response.use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                        parseAjaxSearchResults(JSONObject(body)).ifEmpty { null }
                    } else null
                }
            }
            if (!ajaxItems.isNullOrEmpty()) return@runCatching ajaxItems
        } catch (e: Exception) {
            ScraperTelemetry.logFailure(source.id, "search_ajax_fallback", e)
        }

        results
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}/browse/?genre[]=${java.net.URLEncoder.encode(genre, "UTF-8")}&page=$page"
        val doc = fetchDocument(url)
        parseMangaCards(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/browse/?m_orderby=views")
        parseMangaCards(doc)
    }

    override suspend fun browseManga(
        page: Int, genre: String?, status: MangaStatus?, type: MangaType?, sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val order = when (sortBy) {
            SortBy.POPULARITY -> "views"
            SortBy.RATING -> "rating"
            SortBy.OLDEST -> "alphabet"
            SortBy.LATEST -> "update"
        }
        val params = mutableListOf("m_orderby=$order", "page=$page")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genre[]=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val doc = fetchDocument("${source.baseUrl}/browse/?${params.joinToString("&")}")
        parseMangaCards(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/browse/")
        doc.select(".genre-tag, .quickfilter .genre-item label")
            .map { it.text().cleanText() }
            .filter { it.length in 2..20 }
            .distinct()
    }

    private fun parseMangaCards(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select("div.manga-card").mapNotNull { card ->
            val linkEl = card.selectFirst("a.card-link-wrap[href*='/manga/']")
                ?: card.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
            val imgEl = card.selectFirst("img")
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null

            val title = card.selectFirst("h3.manga-title a, h3.manga-title")?.text()?.cleanText()
                ?: imgEl?.attr("alt")?.cleanText()
                ?: slug

            val coverUrl = imgEl?.let {
                it.attr("abs:src").ifEmpty { it.attr("data-src").absoluteUrl() }
            }.orEmpty()

            val typeText = card.selectFirst(".manga-meta span:first-child")?.text()?.cleanText()
            val type = MangaType.from(typeText)

            MangaItem(
                id = "${source.id}_$slug",
                slug = slug,
                title = title,
                coverUrl = coverUrl,
                source = source,
                type = type,
                url = href
            )
        }.distinctBy { it.id }
    }

    /**
     * Parse AJAX search results from ts_ac_do_search action.
     * Response format: {"series": [{"all": [...], "template": "...", "title": "Search"}]}
     */
    private fun parseAjaxSearchResults(json: JSONObject): List<MangaItem> {
        val results = mutableListOf<MangaItem>()
        val series = json.optJSONArray("series") ?: return results
        // series is a JSONArray of objects, each with "all" array
        for (i in 0 until series.length()) {
            val category = series.optJSONObject(i) ?: continue
            val allArray = category.optJSONArray("all") ?: continue
            for (j in 0 until allArray.length()) {
                val item = allArray.optJSONObject(j) ?: continue
                val postLink = item.optString("post_link", "")
                val postTitle = item.optString("post_title", "")
                val postImage = item.optString("post_image_html", "")
                if (postLink.isBlank()) continue
                val slug = postLink.trimEnd('/').substringAfterLast("/").substringBefore("?")
                val coverImg = Regex("""src="([^"]+)"""").find(postImage)?.groupValues?.get(1) ?: ""
                results.add(
                    MangaItem(
                        id = "${source.id}_$slug",
                        slug = slug,
                        title = postTitle.cleanText().ifBlank { slug },
                        coverUrl = coverImg.absoluteUrl(),
                        source = source,
                        url = postLink.absoluteUrl()
                    )
                )
            }
        }
        return results.distinctBy { it.id }
    }
}
