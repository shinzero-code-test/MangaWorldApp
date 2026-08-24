package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Reusable scraper for WordPress + Madara theme sites.
 *
 * Madara sites use:
 * - AJAX-based manga listing at wp-admin/admin-ajax.php
 * - AJAX-based chapter loading
 * - POST-based search
 * - Standard CSS selectors for manga cards and detail pages
 *
 * Subclasses only need to override domain-specific overrides.
 */
open class MadaraBaseScraper(
    client: OkHttpClient,
    source: MangaSource,
    settingsRepo: SettingsRepository,
    protected val datePattern: String = "d MMMM، yyyy",
    protected val ajaxSearchAction: String = "madara_load_more"
) : BaseScraperImpl(client, source, settingsRepo) {

    protected open val listPath: String = "/manga/"

    protected fun parseArabicDate(text: String): Long? = ScraperText.parseArabicDate(text)

    // ─── Home ─────────────────────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument(source.baseUrl)
        val latestChapters = parseMadaraLatestChapters(doc)
        val popular = parseMadaraPopularManga(doc)

        HomeData(
            featured = popular.take(8),
            latestChapters = latestChapters.take(30),
            trending = popular.distinctBy { it.id }
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        // Progressive URL resolution: try /manga/ → /comics/ → /manhwa/ → bare /
        // Some Madara sites use different path prefixes for manga detail pages.
        // Check for DETAIL-SPECIFIC elements (not just any Madara element) to avoid
        // matching library/list pages which may have .post-title in manga cards.
        val isMangaDetailPage: (org.jsoup.nodes.Document?) -> Boolean = { d ->
            d != null && (
                // Must have a summary_image OR h1.entry-title AND chapters listing
                (d.selectFirst(".summary_image, .profile-manga .summary_image") != null) ||
                (d.selectFirst("h1.entry-title") != null && d.selectFirst(".listing-chapters_wrap, .eplister, #chapterlist") != null)
            )
        }
        val pathsToTry = listOf("/manga/", "/comics/", "/manhwa/")
        var url = ""
        var doc: org.jsoup.nodes.Document? = null

        for (path in pathsToTry) {
            val tryUrl = "${source.baseUrl}$path$slug/"
            val tryDoc = runCatching { fetchDocument(tryUrl) }.getOrNull()
            if (tryDoc != null) {
                val isExplicit404 = tryDoc.selectFirst("body.error-404, body.page-not-found, .page-404, .error-page") != null
                if (!isExplicit404 && isMangaDetailPage(tryDoc)) {
                    url = tryUrl
                    doc = tryDoc
                    break
                }
            }
        }
        // Last resort: try the slug directly on the base URL (for hijala-like sites)
        if (doc == null) {
            val bareUrl = "${source.baseUrl}/$slug/"
            val bareDoc = runCatching { fetchDocument(bareUrl) }.getOrNull()
            if (bareDoc != null && isMangaDetailPage(bareDoc)) {
                url = bareUrl
                doc = bareDoc
            }
        }
        doc ?: error("Could not load manga detail for $slug")

        val coverUrl = doc.selectFirst(".summary_image img, .profile-manga img, img.wp-post-image, .thumb img, img.img-responsive")
            ?.let { img ->
                img.attr("abs:src").ifEmpty {
                    (img.attr("data-src").ifEmpty { img.attr("src") }).absoluteUrl()
                }
            } ?: ""

        val title = doc.selectFirst(".post-title h1, .post-title, h1.entry-title, h1")?.text()?.cleanText() ?: slug

        val description = doc.selectFirst(".description-summary p, .summary__content p, .manga-excerpt, .summary__content h4")
            ?.text()?.cleanText()
            ?: doc.selectFirst(".description-summary, .summary__content, .manga-excerpt")?.text()?.cleanText()
            ?: ""

        // Status
        val statusEl = doc.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading")?.text()?.let {
                it.contains("الحالة") || it.contains("Status")
            } == true
        }
        val status = MangaStatus.from(statusEl?.selectFirst(".summary-content")?.text()?.cleanText())

        fun metaValue(label: String): String? {
            return doc.select(".post-content_item").firstOrNull { item ->
                item.selectFirst(".summary-heading")?.text()?.contains(label) == true
            }?.selectFirst(".summary-content")?.text()?.cleanText()?.ifBlank { null }
        }

        val alternativeTitles = metaValue("Alternative")
            ?.split("/", "|", "،", ",")
            ?.map { it.cleanText() }
            ?.filter { it.isNotBlank() }
            ?.distinct() ?: emptyList()

        val authorName = metaValue("الكاتب") ?: metaValue("Author")
        val artistName = metaValue("الرسام") ?: metaValue("Artist")

        val genres = doc.select(".genres-content a, .c-cat-list a").map { it.text().cleanText() }
            .ifEmpty {
                doc.select(".post-content_item").firstOrNull { item ->
                    item.selectFirst(".summary-heading")?.text()?.let {
                        it.contains("التصنيف") || it.contains("Genre")
                    } == true
                }?.select(".summary-content a")?.map { it.text().cleanText() } ?: emptyList()
            }
            .filter { it.isNotBlank() }

        val bodyText = doc.body().text()
        val type = MangaType.from(
            when {
                bodyText.contains("مانهوا كورية", true) || bodyText.contains("manhwa", true) -> "manhwa"
                bodyText.contains("مانهوا صينية", true) || bodyText.contains("manhua", true) -> "manhua"
                bodyText.contains("مانجا", true) || bodyText.contains("manga", true) -> "manga"
                else -> null
            }
        )

        // Try to get full chapter list via AJAX
        val chapters = tryAjaxChapters(doc, slug, url)

        val viewsText = ScraperText.extractViews(doc.body().text())?.toString()

        MangaDetail(
            id = "${source.id}_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            alternativeTitles = alternativeTitles,
            authorName = authorName,
            artistName = artistName,
            description = description,
            genres = genres,
            tags = genres,
            status = status,
            type = type,
            views = viewsText,
            totalChapters = chapters.size,
            chapters = chapters,
            url = url
        )
    }

    // ─── Chapter Pages ────────────────────────────────────────────────────────

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl, extraHeaders = mapOf("Referer" to source.baseUrl + "/"))

        // Madara theme: .reading-content .page-break img
        doc.select(".reading-content .page-break img, .reading-content img, .page-break img, img.wp-manga-chapter-img")
            .mapNotNull { img ->
                // For lazy-loaded images, src may be a base64 placeholder or SVG.
                // Always prefer data-src if present, otherwise use abs:src but skip data: URIs.
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
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(
                    index = index,
                    url = src,
                    headers = buildImageHeaders(src, chapterUrl)
                )
            }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // Try standard GET search first
        val url = "${source.baseUrl}/?s=$encoded&post_type=wp-manga&paged=$page"
        val doc = fetchDocument(url)
        val results = parseMangaGrid(doc)
        if (results.isNotEmpty()) return@runCatching results

        // Fallback: try /{listPath}/ path search — WP archives paginate via
        // /page/N/, not ?page= (which repeats page-1 results) (L-review).
        val url2 = if (page <= 1) {
            "${source.baseUrl}${listPath}?s=$encoded"
        } else {
            "${source.baseUrl}${listPath}page/$page/?s=$encoded"
        }
        val doc2 = runCatching { fetchDocument(url2) }.getOrNull()
        if (doc2 != null) {
            val results2 = parseMangaGrid(doc2)
            if (results2.isNotEmpty()) return@runCatching results2
        }
        results
    }

    // ─── Browse ───────────────────────────────────────────────────────────────

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}${listPath}?genre=${java.net.URLEncoder.encode(genre, "UTF-8")}&page=$page"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}${listPath}?m_orderby=views"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val order = when (sortBy) {
            SortBy.POPULARITY -> "views"
            SortBy.RATING -> "rating"
            SortBy.OLDEST -> "alphabet"
            SortBy.LATEST -> "latest"
        }
        val pagePart = if (page <= 1) "${source.baseUrl}${listPath}" else "${source.baseUrl}${listPath}page/$page/"
        val params = mutableListOf("m_orderby=$order")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genre=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val doc = fetchDocument(pagePart + "?" + params.joinToString("&"))
        parseMangaGrid(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}${listPath}")
        doc.select("ul.genre-scroll-list li a, .genres-content a, .madara-dropdown .genre-item a")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    protected open fun parseMadaraLatestChapters(doc: org.jsoup.nodes.Document): List<LatestChapterItem> {
        val latestItems = mutableListOf<LatestChapterItem>()

        doc.select("div.page-item-detail.manga, .c-blog-listing .page-item-detail").forEach { card ->
            val imgEl = card.selectFirst(".item-thumb img.img-responsive, .item-thumb img") ?: return@forEach
            val linkEl = card.selectFirst(".item-thumb a") ?: return@forEach
            val titleEl = card.selectFirst(".post-title a, .post-title h3 a, .item-summary .post-title a")

            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            // Works for /manga/, /comics/, /manhwa/ layouts: take the last path
            // segment (query-stripped). substringAfterLast("/manga/") returned the
            // whole URL when the delimiter was absent, corrupting slugs (H-review).
            val slug = href.substringBefore("?").trimEnd('/').substringAfterLast('/').trimEnd('/')
            if (slug.isEmpty()) return@forEach

            val coverUrl = imgEl.attr("abs:src").ifEmpty {
                (imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }).absoluteUrl()
            }
            val title = titleEl?.text()?.cleanText() ?: slug
            val mangaId = "${source.id}_$slug"

            card.select("a.btn-link[href*='/manga/'], .chapter-item a, a.btn-link").take(2).forEach chLoop@{ chLink ->
                val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
                val chNum = chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                    ?: chLink.text().replace("[^0-9.]".toRegex(), "").trim().toFloatOrNull()
                    ?: return@chLoop

                val timeEl = chLink.closest(".chapter-item, .list-chapter, .item-summary")
                    ?.selectFirst(".post-on, .chapter-release-date")
                val timeText = timeEl?.text()?.cleanText().orEmpty()

                latestItems.add(
                    LatestChapterItem(
                        mangaId = mangaId,
                        mangaSlug = slug,
                        mangaTitle = title,
                        coverUrl = coverUrl,
                        chapterNumber = chNum,
                        chapterUrl = chHref,
                        timeAgo = timeText,
                        source = source,
                        isNew = timeText.contains("ساعة") || timeText.contains("hour") ||
                                timeText.contains("دقيقة") || timeText.contains("minute") ||
                                timeText.contains("ثانية") || timeText.contains("second") ||
                                timeText.contains("لحظات") || timeText.contains("moments")
                    )
                )
            }
        }
        return latestItems.distinctBy { it.chapterUrl }
    }

    protected open fun parseMadaraPopularManga(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select(".widget-manga-recent .popular-item-wrap, .sidebar .popular-item-wrap").mapNotNull { item ->
            val img = item.selectFirst(".popular-img img, img") ?: return@mapNotNull null
            val link = item.selectFirst("a[href*='/manga/'], a[href*='/comics/'], a[href*='/manhwa/']") ?: return@mapNotNull null
            val title = item.selectFirst(".post-title a, .popular-title")?.text()?.cleanText()
                ?: link.attr("title").cleanText()
            val href = link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }
            // Works for /manga/, /comics/, /manhwa/ layouts: take the last path
            // segment (query-stripped). substringAfterLast("/manga/") returned the
            // whole URL when the delimiter was absent, corrupting slugs (H-review).
            val slug = href.substringBefore("?").trimEnd('/').substringAfterLast('/').trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null
            MangaItem(
                id = "${source.id}_$slug",
                slug = slug,
                title = title,
                coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() },
                source = source,
                url = href
            )
        }
    }

    protected open fun parseMangaGrid(doc: org.jsoup.nodes.Document): List<MangaItem> {
        val results = doc.select(
            "div.page-item-detail.manga, div.c-tabs-item__content, div.row.c-tabs-item__content, " +
            "div.c-image-hover, .c-blog-listing .page-item-detail, .page-item-detail"
        ).mapNotNull { card ->
            val imgEl = card.selectFirst("img.img-responsive, .item-thumb img, img[src], img[data-src]") ?: return@mapNotNull null
            val linkEl = card.selectFirst(".post-title a[href], .item-thumb a[href], a[href*=\"/manga/\"], a[href*=\"manga/\"]")
                ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            // Extract slug from various URL patterns: /manga/slug/, /comics/slug/
            // Strip query params first to avoid polluting identifiers
            val slug = href.substringBefore("?").trimEnd('/').let { url ->
                val lastSegment = url.substringAfterLast("/")
                if (lastSegment.isNotBlank() && !lastSegment.startsWith("page")) lastSegment else url.substringAfterLast("/manga/").substringAfterLast("/comics/")
            }.trimEnd('/')
            if (slug.isEmpty()) return@mapNotNull null
            val title = card.selectFirst(".post-title a, h3 a, .post-title h3 a, .item-summary .post-title a")?.text()?.cleanText()
                ?: linkEl.attr("title").cleanText().ifBlank { slug }
            if (title.isBlank()) return@mapNotNull null
            MangaItem(
                id = "${source.id}_$slug",
                slug = slug,
                title = title,
                coverUrl = imgEl.attr("abs:src").ifEmpty {
                    (imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }).absoluteUrl()
                },
                source = source,
                url = href
            )
        }
        return results.distinctBy { it.id }
    }

    private suspend fun tryAjaxChapters(
        doc: org.jsoup.nodes.Document,
        slug: String,
        pageUrl: String
    ): List<Chapter> {
        val allChapters = mutableListOf<Chapter>()

        // Parse inline chapters first
        doc.select(".listing-chapters_wrap li, .wp-manga-chapter, li.wp-manga-chapter").forEach { li ->
            parseChapterLi(li, slug)?.let { allChapters.add(it) }
        }

        // Try AJAX for full chapter list — use the /ajax/chapters/ pattern
        // (which is the standard Madara endpoint used by kotatsu parsers)
        try {
            val ajaxUrl = "${pageUrl.trimEnd('/')}/ajax/chapters/"
            val ajaxRequest = Request.Builder()
                .url(ajaxUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Referer", pageUrl.encodeForHeader())
                .header("X-Requested-With", "XMLHttpRequest")
                .post(FormBody.Builder().build())
                .build()

            val body = withContext(Dispatchers.IO) {
                client.newCall(ajaxRequest).execute().use { response ->
                    response.body?.string() ?: ""
                }
            }

            if (body.isNotBlank() && body.contains("wp-manga-chapter")) {
                val chapDoc = Jsoup.parse(body, source.baseUrl)
                val ajaxChapters = chapDoc.select("li.wp-manga-chapter, li")
                    .mapNotNull { li -> parseChapterLi(li, slug) }
                if (ajaxChapters.isNotEmpty()) {
                    allChapters.clear()
                    allChapters.addAll(ajaxChapters)
                }
            }
        } catch (e: Exception) {
            FirebaseTelemetry.logScraperFailure(source.id, "chapters_ajax", e)
        }

        // Fallback: try wp-admin AJAX (some Madara sites use this instead)
        if (allChapters.isEmpty()) {
            try {
                val postId = doc.selectFirst("input.rating-post-id")?.attr("value")
                    ?: doc.selectFirst("body")?.let { body ->
                        body.classNames().firstOrNull { it.startsWith("postid-") }
                            ?.removePrefix("postid-")
                    }

                if (!postId.isNullOrBlank()) {
                    for (action in listOf("wp-manga-get-chapters", "manga-get-chapters")) {
                        val formBody = FormBody.Builder()
                            .add("action", action)
                            .add("manga", postId)
                            .build()
                        val ajaxRequest = Request.Builder()
                            .url("${source.baseUrl}/wp-admin/admin-ajax.php")
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "*/*")
                            .header("Referer", pageUrl.encodeForHeader())
                            .header("X-Requested-With", "XMLHttpRequest")
                            .post(formBody)
                            .build()
                        val (bodyStr, json) = withContext(Dispatchers.IO) {
                            val response = client.newCall(ajaxRequest).execute()
                            val body = response.use { it.body?.string() ?: "{}" }
                            body to JSONObject(body)
                        }
                        if (json.optBoolean("success", false)) {
                            val html = json.optString("data", "")
                            val chapDoc = Jsoup.parse(html, source.baseUrl)
                            val ajaxChapters = chapDoc.select("li").mapNotNull { li -> parseChapterLi(li, slug) }
                            if (ajaxChapters.isNotEmpty()) {
                                allChapters.addAll(ajaxChapters)
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FirebaseTelemetry.logScraperFailure(source.id, "chapters_admin_ajax", e)
            }
        }

        return allChapters.distinctBy { it.url }.sortedByDescending { it.number }
    }

    private fun parseChapterLi(li: org.jsoup.nodes.Element, slug: String): Chapter? {
        val chLink = li.selectFirst("a[href]") ?: return null
        val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
        val chText = chLink.text().cleanText()
        // Extract ONLY the first number — prevents "الفصل 12 : 3 وحوش" → 123
        val chNum = Regex("[0-9]+(?:\\.[0-9]+)?").find(chText)?.value?.toFloatOrNull()
            ?: chHref.trimEnd('/').substringAfterLast("/").substringBefore("?").toFloatOrNull()
            ?: return null
        val dateText = li.selectFirst(".chapter-release-date .timediff i, .chapter-release-date i, .chapter-release-date span, .post-on")?.text()?.cleanText()
        val dateLong = dateText?.let { parseArabicDate(it) }
        return Chapter(
            id = "${slug}_$chNum",
            mangaId = "${source.id}_$slug",
            number = chNum,
            title = chText.replace(Regex("الفصل\\s*[0-9.,]+\\s*[:.]*\\s*"), "").trim().ifBlank { null },
            url = chHref,
            date = dateLong,
            dateText = dateText
        )
    }
}
