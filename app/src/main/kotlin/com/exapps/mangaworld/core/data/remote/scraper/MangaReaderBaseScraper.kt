package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reusable scraper for WordPress + MangaReader theme sites.
 *
 * MangaReader sites use:
 * - Non-AJAX manga listing (direct page loads)
 * - `.listupd .bs .bsx` card structure
 * - `img.ts-post-image` for cover images
 * - `div.tt` for titles
 * - `#chapterlist > ul > li` for chapters
 * - `div#readerarea img` for page images
 *
 * Subclasses only need to pass the correct domain and config.
 */
open class MangaReaderBaseScraper(
    client: OkHttpClient,
    source: MangaSource,
    settingsRepo: SettingsRepository,
    protected val pageSize: Int = 24,
    protected val searchPageSize: Int = 10
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
        val latestChapters = parseLatestChapters(doc)
        val popular = parsePopularManga(doc)

        HomeData(
            featured = popular.take(8),
            latestChapters = latestChapters.take(30),
            trending = popular
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/manga/$slug/"
        val doc = fetchDocument(url)

        val coverUrl = doc.selectFirst(".imgseries img, .postbody img, .thumb img, .sorthumb img")
            ?.let { img ->
                img.attr("abs:src").ifEmpty {
                    (img.attr("data-src").ifEmpty { img.attr("src") }).absoluteUrl()
                }
            } ?: ""

        val title = doc.selectFirst("h1.entry-title, .seriestucont h1, .bigcontent .infox h1")
            ?.text()?.cleanText() ?: slug

        val description = doc.selectFirst(".entry-content, .seriestucont .seriestucontr .wd-full, div.description")
            ?.text()?.cleanText() ?: ""

        // Status from info table
        val statusText = doc.select(".tsinfo div, .infotable td").firstOrNull { el ->
            el.text().contains("الحالة") || el.text().contains("Status", ignoreCase = true)
        }?.nextElementSibling()?.text()?.cleanText()
            ?: doc.select(".postbody .imptdt .status, .seriestucontr .tsinfo .imptdt .status i")
                .firstOrNull()?.text()?.cleanText()
        val status = MangaStatus.from(statusText)

        val genres = doc.select(".seriestugenre a, .wd-full .mgen > a, .genre-info a, ul.genrez li label")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
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

        // Chapters: standard #chapterlist
        val chapters = doc.select("#chapterlist > ul > li, .eplister > ul > li, .bxcl ul li").mapNotNull { li ->
            val chLink = li.selectFirst("a[href]") ?: return@mapNotNull null
            val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
            val chNum = li.attr("data-num").toFloatOrNull()
                ?: chLink.selectFirst(".chapternum")?.text()?.replace("الفصل", "")
                    ?.replace("[^0-9.]".toRegex(), "")?.trim()?.toFloatOrNull()
                ?: chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                ?: return@mapNotNull null
            val dateText = li.selectFirst(".chapterdate, .dt a")?.text()?.cleanText()
            val dateLong = dateText?.let { parseArabicDate(it) }
            val title = chLink.selectFirst(".chapternum")?.text()?.cleanText()
            Chapter(
                id = "${slug}_$chNum",
                mangaId = "${source.id}_$slug",
                number = chNum,
                title = title?.replace("الفصل", "")?.trim()?.ifBlank { null },
                url = chHref,
                date = dateLong,
                dateText = dateText
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

    // ─── Chapter Pages ────────────────────────────────────────────────────────

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl, extraHeaders = mapOf("Referer" to source.baseUrl + "/"))

        // MangaReader/MangaStream themes:
        // - Standard: div#readerarea img, div#content .ts-main-image
        // - Lavascans: .reader-area .ts-main-image
        // - StellarSaber: #readerarea .ts-main-image
        // - Hijala: .readercontent .ts-main-image
        doc.select(
            "div#readerarea img, div#content img, .reader-area img, .readercontent img, " +
            ".reading-content img, img.ts-main-image, .chapter-pages img"
        )
            .mapNotNull { img ->
                val src = img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty { img.attr("src") }.absoluteUrl()
                }.encodeForUrl()
                src.takeIf { it.isNotBlank() && !it.contains("logo") && !it.contains("avatar") }
            }
            .filter { it.contains(".jpg") || it.contains(".png") || it.contains(".webp") || it.contains("wp-content") }
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
        val url = if (page <= 1) "${source.baseUrl}/?s=$encoded" else "${source.baseUrl}/page/$page/?s=$encoded"
        val doc = fetchDocument(url)
        parseMangaCards(doc)
    }

    // ─── Browse ───────────────────────────────────────────────────────────────

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}${listPath}?genre[]=${java.net.URLEncoder.encode(genre, "UTF-8")}&page=$page"
        val doc = fetchDocument(url)
        parseMangaCards(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}${listPath}?order=popular"
        val doc = fetchDocument(url)
        parseMangaCards(doc)
    }

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val order = when (sortBy) {
            SortBy.POPULARITY -> "popular"
            SortBy.RATING -> "rating"
            SortBy.OLDEST -> "title"
            SortBy.LATEST -> "update"
        }
        val params = mutableListOf("order=$order", "page=$page")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genre[]=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val doc = fetchDocument("${source.baseUrl}${listPath}?${params.joinToString("&")}")
        parseMangaCards(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}${listPath}")
        doc.select(".quickfilter .genrez label, .advanced-search .genres label, ul.genrez li label")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    protected open fun parseLatestChapters(doc: org.jsoup.nodes.Document): List<LatestChapterItem> {
        val latestItems = mutableListOf<LatestChapterItem>()

        doc.select(".listupd .bs .bsx, .bixbox.hothome .bs .bsx, .big-slider .swiper-slide").forEach { card ->
            val linkEl = card.selectFirst("a[href*='/manga/']") ?: return@forEach
            val imgEl = card.selectFirst("img")
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            if (slug.isBlank()) return@forEach

            val coverUrl = imgEl?.let {
                it.attr("abs:src").ifEmpty {
                    (it.attr("data-src").ifEmpty { it.attr("src") }).absoluteUrl()
                }
            }.orEmpty()

            val title = card.selectFirst(".tt, .bigor .adds .epxs")?.text()?.cleanText()
                ?: linkEl.attr("title").cleanText().ifBlank { slug }

            latestItems.add(
                LatestChapterItem(
                    mangaId = "${source.id}_$slug",
                    mangaSlug = slug,
                    mangaTitle = title,
                    coverUrl = coverUrl,
                    chapterNumber = 0f,
                    chapterUrl = href,
                    timeAgo = "",
                    source = source
                )
            )
        }
        return latestItems.distinctBy { it.mangaId }
    }

    protected open fun parsePopularManga(doc: org.jsoup.nodes.Document): List<MangaItem> {
        // Try sidebar popular widget first, then fall back to the main list
        val sidebar = doc.select(".popular-item-wrap, .widget_manga_popular .popular-item-wrap, .sidebar .popular-item-wrap").mapNotNull { item ->
            val img = item.selectFirst("img") ?: return@mapNotNull null
            val link = item.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
            val title = item.selectFirst(".post-title a, .popular-title, h3 a")?.text()?.cleanText()
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
        }.distinctBy { it.id }
        return if (sidebar.isNotEmpty()) sidebar.take(10)
        else parseMangaCards(doc).take(10)
    }

    protected open fun parseMangaCards(doc: org.jsoup.nodes.Document): List<MangaItem> {
        val results = mutableListOf<MangaItem>()

        // Pattern 1: Standard MangaReader — .listupd .bs .bsx
        doc.select(
            ".listupd .bs .bsx, .bs .bsx, .bsx, .bixbox .bsx, .listupd .bs"
        ).forEach { card ->
            val linkEl = card.selectFirst("a[href*='/manga/'], a[href*='manga/'], a[href]")
                ?: return@forEach
            val imgEl = card.selectFirst("img")
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringBefore("?").trimEnd('/').let { url ->
                val segments = url.split("/")
                val last = segments.lastOrNull() ?: ""
                if (last.isNotBlank() && !last.startsWith("page")) last
                else segments.dropLast(1).lastOrNull() ?: ""
            }.trimEnd('/')
            if (slug.isBlank() || slug == "manga") return@forEach
            val title = card.selectFirst(".tt, .bigor .tt, h3 a, .entry-title, .post-title a")?.text()?.cleanText()
                ?: imgEl?.attr("alt")?.cleanText()
                ?: linkEl.attr("title").cleanText().ifBlank { slug }
            if (title.isBlank()) return@forEach
            val coverUrl = imgEl?.let {
                it.attr("abs:src").ifEmpty {
                    (it.attr("data-src").ifEmpty { it.attr("src") }).absoluteUrl()
                }
            }.orEmpty()
            results.add(
                MangaItem(
                    id = "${source.id}_$slug",
                    slug = slug,
                    title = title,
                    coverUrl = coverUrl,
                    source = source,
                    url = href
                )
            )
        }

        // Pattern 2: Lavascans — .manga-list-grid article.legend-card
        if (results.isEmpty()) {
            doc.select("article.legend-card").forEach { card ->
                val linkEl = card.selectFirst("a.legend-poster[href*='/manga/']") ?: return@forEach
                val imgEl = card.selectFirst("img.legend-img, img")
                val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
                val slug = href.substringBefore("?").trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
                if (slug.isBlank()) return@forEach
                val title = imgEl?.attr("alt")?.cleanText()
                    ?: card.selectFirst(".legend-info h3, .legend-title")?.text()?.cleanText()
                    ?: slug
                val coverUrl = imgEl?.let {
                    it.attr("abs:src").ifEmpty { it.attr("src").absoluteUrl() }
                }.orEmpty()
                results.add(
                    MangaItem(
                        id = "${source.id}_$slug",
                        slug = slug,
                        title = title,
                        coverUrl = coverUrl,
                        source = source,
                        url = href
                    )
                )
            }
        }

        // Pattern 3: StellarSaber sidebar — .listupd li .imgseries
        if (results.isEmpty()) {
            doc.select(".listupd li, .serieslist li").forEach { li ->
                val linkEl = li.selectFirst("a.series[href*='/manga/'], a[href*='/manga/']") ?: return@forEach
                val imgEl = li.selectFirst("img.ts-post-image, img")
                val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
                val slug = href.substringBefore("?").trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
                if (slug.isBlank()) return@forEach
                val title = linkEl.text().cleanText().ifBlank {
                    imgEl?.attr("alt")?.cleanText() ?: slug
                }
                val coverUrl = imgEl?.let {
                    it.attr("abs:src").ifEmpty { it.attr("src").absoluteUrl() }
                }.orEmpty()
                results.add(
                    MangaItem(
                        id = "${source.id}_$slug",
                        slug = slug,
                        title = title,
                        coverUrl = coverUrl,
                        source = source,
                        url = href
                    )
                )
            }
        }

        return results.distinctBy { it.id }
    }
}
