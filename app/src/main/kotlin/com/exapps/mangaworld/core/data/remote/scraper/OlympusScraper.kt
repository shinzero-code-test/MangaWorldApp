package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Scraper for olympustaff.com (Team-X)
 *
 * CSS Selectors (from HTML analysis):
 * HOME:
 *   Manga cards   → .box > .uta
 *   Cover image   → .box .imgu img [src]
 *   Manga link    → .box .imgu a [href]
 *   Title         → .box .info h3
 *   Latest chapter→ .box .info a.new (or ul li a with chapter info)
 *   Popular       → .popular-manga .entry-box
 *   Popular img   → .entry-box img.best-img
 *   Popular title → .entry-box .entry-title a
 *
 * DETAIL:
 *   Cover         → img[alt="Manga Image"].shadow-sm
 *   Title         → .col-md-9 h1
 *   Genres        → .review-author-info a.subtitle
 *   Description   → .col-md-9 p (first substantial paragraph)
 *   Chapter cards → .chapter-card[data-number][data-date][data-views]
 *   Chapter link  → .chapter-card a.chapter-link[href]
 *   Chapter title → .chapter-card .chapter-title
 *   Paid chapters → .fa-lock (chapter is paywalled)
 *
 * CHAPTER:
 *   Pages         → .reading-content .page-break img.manga-chapter-img [src]
 *   Prev chapter  → a#prev-chapter [href]
 *   Next chapter  → a#next-chapter [href]
 *
 * URL Patterns:
 *   Home       → /
 *   Series     → /series/{slug}
 *   Chapter    → /series/{slug}/{chapter_number}
 *   Browse     → /series?genre={genre}
 *   Search AJAX→ /ajax/search?keyword={q}
 */
class OlympusScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.OLYMPUS, settingsRepo) {

    // ─── Home ─────────────────────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument(resolvedBaseUrl)

        // Latest updates: skip empty hero slots and '#' placeholders.
        val latestBoxSelector = remoteSelector("home_latest_boxes", ".box:has(.imgu a[href*='/series/']):has(.info h3)")
        val latestTitleSelector = remoteSelector("home_latest_title", ".info h3")
        val latestLinkSelector = remoteSelector("home_latest_link", ".imgu a[href*='/series/']")
        val latestImageSelector = remoteSelector("home_latest_image", ".imgu img")
        val latestChapterSelector = remoteSelector("home_latest_chapter_links", ".info a[href*='/series/']")
        val latestChapters = doc.select(latestBoxSelector)
            .take(40)
            .mapNotNull { box ->
                val imgEl = box.selectFirst(latestImageSelector) ?: return@mapNotNull null
                val linkEl = box.selectFirst(latestLinkSelector) ?: return@mapNotNull null
                val titleEl = box.selectFirst(latestTitleSelector) ?: return@mapNotNull null

                val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
                val slug = href.substringAfterLast("/series/").trimEnd('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mangaId = "olympus_$slug"

                val chapterEl = box.select(latestChapterSelector)
                    .firstOrNull { anchor ->
                        val candidate = anchor.attr("abs:href").ifEmpty { anchor.attr("href").absoluteUrl() }
                        candidate.contains("/series/$slug/") && candidate.substringAfterLast('/').toFloatOrNull() != null
                    }

                val chapterHref = chapterEl?.attr("abs:href")
                    ?.ifEmpty { chapterEl.attr("href").absoluteUrl() }
                    ?.takeIf { it.contains("/series/$slug/") }
                    ?: return@mapNotNull null

                val chapterNumber = chapterHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                    ?: chapterEl.text().replace("[^0-9.]".toRegex(), "").toFloatOrNull()
                    ?: return@mapNotNull null

                val timeText = box.selectFirst(".info ul li .post-on, .info .post-on")?.text()?.cleanText().orEmpty()

                LatestChapterItem(
                    mangaId = mangaId,
                    mangaSlug = slug,
                    mangaTitle = titleEl.text().cleanText(),
                    coverUrl = imgEl.attr("abs:src").ifEmpty { imgEl.attr("src").absoluteUrl() }.encodeForUrl(),
                    chapterNumber = chapterNumber,
                    chapterUrl = chapterHref,
                    timeAgo = timeText,
                    source = source,
                    isNew = timeText.contains("ساعة") || timeText.contains("hour")
                )
            }
            .distinctBy { it.chapterUrl }

        // Popular manga: .popular-manga .entry-box
        val popular = doc.select(remoteSelector("popular_cards", ".popular-manga .entry-box, .entry-box.entry-box-1")).take(10)
            .mapNotNull { box ->
                val img = box.selectFirst(remoteSelector("popular_image", "img.best-img")) ?: return@mapNotNull null
                val titleEl = box.selectFirst(remoteSelector("popular_title", ".entry-title a, h3 a")) ?: return@mapNotNull null
                val linkEl = box.selectFirst(remoteSelector("popular_link", "a.box, .entry-image a")) ?: return@mapNotNull null

                val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
                val slug = href.substringAfterLast("/series/").trimEnd('/')
                MangaItem(
                    id = "olympus_$slug",
                    slug = slug,
                    title = titleEl.text().cleanText(),
                    coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() }.encodeForUrl(),
                    source = source,
                    url = href
                )
            }
            .distinctBy { it.url }

        HomeData(
            featured = popular.take(5),
            latestChapters = latestChapters,
            trending = popular
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${resolvedBaseUrl}/series/$slug"
        val doc = fetchDocument(url)

        fun lazySrc(element: org.jsoup.nodes.Element?): String? {
            val el = element ?: return null
            val dataSrc = el.attr("data-src").ifBlank { el.attr("data-lazy-src") }
            if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) return dataSrc
            val abs = el.attr("abs:src")
            if (abs.isNotBlank() && !abs.startsWith("data:")) return abs
            val src = el.attr("src")
            if (src.isNotBlank() && !src.startsWith("data:")) return src
            return null
        }
        val coverUrl = lazySrc(doc.selectFirst("img[alt=\"Manga Image\"]"))
            ?: lazySrc(doc.selectFirst(".text-right img.shadow-sm, .shadow-sm"))
            ?: ""

        // Title: h1 is the most reliable; .col-md-9 doesn't exist in real HTML
        val title = doc.selectFirst("h1")?.text()?.cleanText()
            ?: doc.selectFirst(".author-info-title h6, .title")?.text()?.cleanText()
            ?: slug

        // Genres: confirmed selector from real HTML — a.subtitle inside .review-author-info
        val genres = doc.select(".review-author-info a.subtitle").map { it.text().cleanText() }
        val tags = genres

        fun fullInfoValue(label: String): String? {
            return doc.select(".full-list-info").firstOrNull { info ->
                info.selectFirst("small")?.text()?.contains(label) == true
            }?.select("small")?.getOrNull(1)?.text()?.cleanText()?.ifBlank { null }
        }

        val artistName = fullInfoValue("الرسام")

        // Description: first long paragraph anywhere in the detail column
        val description = doc.select("p")
            .firstOrNull { it.text().length > 30 && !it.text().contains("http") }
            ?.text()?.cleanText() ?: ""

        // Status from text content
        val infoText = doc.select(".col-md-9").text()
        val status = MangaStatus.from(
            when {
                infoText.contains("مستمرة") || infoText.contains("مستمر") -> "ongoing"
                infoText.contains("مكتملة") || infoText.contains("مكتمل") -> "completed"
                else -> null
            }
        )

        val type = MangaType.from(
            when {
                infoText.contains("مانهوا", true) || infoText.contains("manhwa", true) -> "manhwa"
                infoText.contains("مانجا", true) || infoText.contains("manga", true) -> "manga"
                infoText.contains("manhua", true) -> "manhua"
                else -> null
            }
        )

        // Chapters: .chapter-card[data-number][data-date][data-views]
        fun parseChapterCards(pageDoc: org.jsoup.nodes.Document): List<Chapter> =
            pageDoc.select(".chapter-card[data-number]").mapNotNull { card ->
            val chapterNum = card.attr("data-number").toFloatOrNull() ?: return@mapNotNull null
            val chapterLink = card.selectFirst("a.chapter-link") ?: return@mapNotNull null
            val chapterHref = chapterLink.attr("abs:href").ifEmpty {
                chapterLink.attr("href").absoluteUrl()
            }
            val chapterTitle = card.selectFirst(".chapter-title")?.text()?.cleanText()
            val chapterCover = card.selectFirst("img.chapter-image")?.attr("abs:src")
                ?.ifBlank { card.selectFirst("img.chapter-image")?.attr("src")?.absoluteUrl() }
                ?.encodeForUrl()
                .orEmpty()
            val dateStr = card.selectFirst(".chapter-date span")?.text()?.cleanText()
            val dateTimestamp = card.attr("data-date").toLongOrNull()?.times(1000L)
            val isPaid = card.selectFirst(".fa-lock") != null

            Chapter(
                id = "${slug}_$chapterNum",
                mangaId = "olympus_$slug",
                number = chapterNum,
                title = chapterTitle,
                url = chapterHref,
                coverUrl = chapterCover,
                date = dateTimestamp,
                dateText = dateStr,
                views = card.attr("data-views").toIntOrNull(),
                isPaid = isPaid
            )
            }

        // Dynamic page count from pagination nav
        val maxPage = doc.select("ul.pagination a.page-link")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull() ?: 1

        val chaptersMap = linkedMapOf<String, Chapter>()
        parseChapterCards(doc).forEach { chaptersMap[it.url] = it }
        for (pageNum in 2..maxPage) {
            val pageDoc = runCatching { fetchDocument("$url?page=$pageNum") }.getOrNull() ?: break
            val pageChapters = parseChapterCards(pageDoc)
            if (pageChapters.isEmpty()) break
            var added = 0
            pageChapters.forEach {
                if (chaptersMap.putIfAbsent(it.url, it) == null) added++
            }
            if (added == 0) break
        }
        val chapters = chaptersMap.values.sortedByDescending { it.number }

        // Views: "X views" text in info area, or sum chapter views
        val viewsText = ScraperText.extractViews(doc.body().text())?.toString()

        val totalChapters = doc.selectFirst(".enhanced-chapters-section h5")
            ?.text()
            ?.replace("[^0-9]".toRegex(), "")
            ?.toIntOrNull() ?: chapters.size

        MangaDetail(
            id = "olympus_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            artistName = artistName,
            description = description,
            genres = genres,
            tags = tags,
            status = status,
            type = type,
            views = viewsText,
            totalChapters = totalChapters,
            chapters = chapters,
            url = url
        )
    }

    // ─── Chapter Pages ────────────────────────────────────────────────────────

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(
            chapterUrl,
            extraHeaders = mapOf("Referer" to resolvedBaseUrl + "/")
        )

        // Pages: .reading-content .page-break img.manga-chapter-img
        val pages = doc.select(".reading-content .page-break img.manga-chapter-img, .reading-content img")
            .mapNotNull { img ->
                // abs:src may be a base64 lazy placeholder — data-src wins; skip data: URIs.
                val absSrc = img.attr("abs:src")
                val dataSrc = img.attr("data-src").ifEmpty { img.attr("data-lazy-src") }
                val chosen = dataSrc.ifBlank {
                    absSrc.takeUnless { it.isBlank() || it.startsWith("data:") } ?: img.attr("src")
                }
                if (chosen.isBlank() || chosen.startsWith("data:")) return@mapNotNull null
                chosen.absoluteUrl().encodeForUrl().takeIf { it.isNotBlank() && !it.startsWith("data:") }
            }
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(
                    index = index,
                    url = src,
                    headers = buildImageHeaders(src, chapterUrl)
                )
            }

        pages
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        // Correct URL format per Kotatsu reference: /?search=<query>&page=<n>
        val doc = fetchDocument("${resolvedBaseUrl}/?search=$encoded&page=$page")
        parseMangaGrid(doc)
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
        val url = "${resolvedBaseUrl}/series?genre=$encodedGenre&page=$page"
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
        val params = mutableListOf("page=$page")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genre=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        status?.takeIf { it != MangaStatus.UNKNOWN }?.let {
            val raw = when (it) {
                MangaStatus.ONGOING -> "مستمرة"
                MangaStatus.COMPLETED -> "مكتمل"
                MangaStatus.HIATUS -> "متوقف"
                MangaStatus.CANCELLED -> "متروك"
                MangaStatus.UNKNOWN -> ""
            }
            if (raw.isNotBlank()) params += "state=${java.net.URLEncoder.encode(raw, "UTF-8")}"
        }
        val doc = fetchDocument("${resolvedBaseUrl}/series?${params.joinToString("&")}")
        parseMangaGrid(doc)
            .filter { type == null || it.type == type || it.type == MangaType.UNKNOWN }
            .let { applyBrowseSort(it, sortBy) }
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument(resolvedBaseUrl)
        doc.select(".popular-manga .entry-box, .entry-box.entry-box-1").mapNotNull { box ->
            val img = box.selectFirst("img.best-img") ?: return@mapNotNull null
            val titleEl = box.selectFirst(".entry-title a, h3 a") ?: return@mapNotNull null
            val linkEl = box.selectFirst("a.box, .entry-image a") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/')
            MangaItem(
                id = "olympus_$slug", slug = slug, title = titleEl.text().cleanText(),
                coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() },
                source = source, url = href
            )
        }
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${resolvedBaseUrl}/series")
        doc.select("a.subtitle[href*=\"genre\"]")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun parseMangaGrid(doc: org.jsoup.nodes.Document): List<MangaItem> {
        // Search results use .listupd .bs .bsx pattern (per Kotatsu reference)
        val searchResults = doc.select(".listupd .bsx, .listupd .bs").mapNotNull { card ->
            val linkEl = card.selectFirst("a[href*='/series/']") ?: return@mapNotNull null
            val imgEl = card.selectFirst("img")
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst(".tt")?.text()?.cleanText()
                ?: card.selectFirst("h3")?.text()?.cleanText()
                ?: imgEl?.attr("alt")?.cleanText()?.ifBlank { null } ?: return@mapNotNull null
            val coverUrl = imgEl?.attr("abs:src")?.replace("thumbnail_", "")?.ifEmpty {
                imgEl.attr("src")?.absoluteUrl()
            }?.encodeForUrl().orEmpty()
            val statusText = card.selectFirst(".status")?.text()?.cleanText()
            val typeText = card.selectFirst(".type")?.text()?.cleanText()
            MangaItem(
                id = "olympus_$slug",
                slug = slug,
                title = title,
                coverUrl = coverUrl,
                source = source,
                status = MangaStatus.from(statusText),
                type = MangaType.from(typeText),
                url = href
            )
        }
        if (searchResults.isNotEmpty()) return searchResults.distinctBy { it.url }

        // Fallback: home page style boxes
        val latestBoxSelector = remoteSelector("home_latest_boxes", ".box:has(.imgu a[href*='/series/']):has(.info h3)")
        val latestTitleSelector = remoteSelector("home_latest_title", ".info h3")
        val latestLinkSelector = remoteSelector("home_latest_link", ".imgu a[href*='/series/']")
        val latestImageSelector = remoteSelector("home_latest_image", ".imgu img")
        val latestChapterSelector = remoteSelector("home_latest_chapter_links", ".info a[href*='/series/']")
        val homeStyle = doc.select(latestBoxSelector).mapNotNull { box ->
            val imgEl = box.selectFirst(latestImageSelector) ?: return@mapNotNull null
            val linkEl = box.selectFirst(latestLinkSelector) ?: return@mapNotNull null
            val titleEl = box.selectFirst(latestTitleSelector) ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterEl = box.select(latestChapterSelector)
                .firstOrNull { anchor ->
                    val candidate = anchor.attr("abs:href").ifEmpty { anchor.attr("href").absoluteUrl() }
                    candidate.contains("/series/$slug/")
                }
            MangaItem(
                id = "olympus_$slug", slug = slug,
                title = titleEl.text().cleanText(),
                coverUrl = imgEl.attr("abs:src").ifEmpty { imgEl.attr("src").absoluteUrl() }.encodeForUrl(),
                source = source,
                latestChapter = chapterEl?.text()?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull()?.toInt(),
                url = href
            )
        }

        val searchCardSelector = remoteSelector("search_cards", ".listupd .bsx")
        val searchLinkSelector = remoteSelector("search_link", "a[href*='/series/']")
        val searchImageSelector = remoteSelector("search_image", "img")
        val searchTitleSelector = remoteSelector("search_title", ".tt")
        val searchStatusSelector = remoteSelector("search_status", ".status")
        val searchTypeSelector = remoteSelector("search_type", ".type")
        val seriesListStyle = doc.select(searchCardSelector).mapNotNull { card ->
            val linkEl = card.selectFirst(searchLinkSelector) ?: return@mapNotNull null
            val imgEl = card.selectFirst(searchImageSelector) ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst(searchTitleSelector)?.text()?.cleanText()
                ?: imgEl.attr("alt").cleanText().ifBlank { return@mapNotNull null }
            val statusText = card.selectFirst(searchStatusSelector)?.text()?.cleanText()
            val typeText = card.selectFirst(searchTypeSelector)?.text()?.cleanText()
            MangaItem(
                id = "olympus_$slug",
                slug = slug,
                title = title,
                coverUrl = imgEl.attr("abs:src").ifEmpty { imgEl.attr("src").absoluteUrl() }.encodeForUrl(),
                source = source,
                status = MangaStatus.from(statusText),
                type = MangaType.from(typeText),
                url = href
            )
        }

        return (homeStyle + seriesListStyle).distinctBy { it.url }
    }

    private fun applyBrowseSort(items: List<MangaItem>, sortBy: SortBy): List<MangaItem> = when (sortBy) {
        SortBy.RATING -> items.sortedByDescending { it.rating ?: 0f }
        // Prefer real view counts when parsed; latestChapter is only a last-resort proxy.
        SortBy.POPULARITY -> items.sortedByDescending { it.latestChapter ?: 0 }
        SortBy.OLDEST -> items.sortedBy { it.title.lowercase() }
        SortBy.LATEST -> items
    }
}
