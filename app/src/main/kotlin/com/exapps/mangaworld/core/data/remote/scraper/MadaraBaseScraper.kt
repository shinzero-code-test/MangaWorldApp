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
import java.text.SimpleDateFormat
import java.util.Locale

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

    private val arabicMonths = mapOf(
        "يناير" to "Jan", "فبراير" to "Feb", "مارس" to "Mar", "أبريل" to "Apr",
        "مايو" to "May", "يونيو" to "Jun", "يوليو" to "Jul", "أغسطس" to "Aug",
        "سبتمبر" to "Sep", "أكتوبر" to "Oct", "نوفمبر" to "Nov", "ديسمبر" to "Dec"
    )

    protected fun parseArabicDate(text: String): Long? {
        if (text.isBlank()) return null
        var normalized = text.trim()
        for ((ar, en) in arabicMonths) {
            normalized = normalized.replace(ar, en)
        }
        return runCatching {
            val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            fmt.parse(normalized)?.time
        }.getOrNull()
    }

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
        // Try /manga/ first, then /comics/ as fallback for some Madara sites
        var url = "${source.baseUrl}/manga/$slug/"
        var doc = runCatching { fetchDocument(url) }.getOrNull()
        if (doc == null || doc.selectFirst("body.error-404, .page-not-found, h1")?.text()?.contains("404") == true) {
            url = "${source.baseUrl}/comics/$slug/"
            doc = fetchDocument(url)
        }
        doc ?: error("Could not load manga detail for $slug")

        val coverUrl = doc.selectFirst(".summary_image img, .profile-manga img, img.wp-post-image")
            ?.let { img ->
                img.attr("abs:src").ifEmpty {
                    (img.attr("data-src").ifEmpty { img.attr("src") }).absoluteUrl()
                }
            } ?: ""

        val title = doc.selectFirst(".post-title h1, h1.entry-title")?.text()?.cleanText() ?: slug

        val description = doc.selectFirst(".description-summary p, .summary__content p, .manga-excerpt")
            ?.text()?.cleanText()
            ?: doc.selectFirst(".description-summary, .summary__content")?.text()?.cleanText()
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

        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)?.replace(",", "")
        }

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
                val src = img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty { img.attr("src") }.absoluteUrl()
                }.encodeForUrl()
                src.takeIf { it.isNotBlank() && !it.contains("logo") && !it.contains("avatar") }
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

        // Fallback: try /manga/ path search
        val url2 = "${source.baseUrl}${listPath}?s=$encoded&page=$page"
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
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
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
            val link = item.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
            val title = item.selectFirst(".post-title a, .popular-title")?.text()?.cleanText()
                ?: link.attr("title").cleanText()
            val href = link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
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
        doc.select(".listing-chapters_wrap li").mapNotNullTo(allChapters) { li ->
            parseChapterLi(li, slug)
        }

        // Try AJAX for full chapter list
        try {
            val postId = doc.selectFirst("input.rating-post-id")?.attr("value")
                ?: doc.selectFirst("body")?.let { body ->
                    body.classNames().firstOrNull { it.startsWith("postid-") }
                        ?.removePrefix("postid-")
                } ?: return allChapters

            if (postId.isBlank()) return allChapters

            for (action in listOf("wp-manga-get-chapters", "manga-get-chapters")) {
                val formBody = FormBody.Builder()
                    .add("action", action)
                    .add("manga", postId)
                    .build()

                val ajaxRequest = Request.Builder()
                    .url("${source.baseUrl}/wp-admin/admin-ajax.php")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "ar,en;q=0.9")
                    .header("Referer", pageUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(formBody)
                    .build()

                val (bodyStr, json) = withContext(Dispatchers.IO) {
                    val response = client.newCall(ajaxRequest).execute()
                    val body = response.body?.string() ?: "{}"
                    response.close()
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
        } catch (_: Exception) { }

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
        val dateText = li.selectFirst(".chapter-release-date i, .chapter-release-date span, .post-on")?.text()?.cleanText()
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
