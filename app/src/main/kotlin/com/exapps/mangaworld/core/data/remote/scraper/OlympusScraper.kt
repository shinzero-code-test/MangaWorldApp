package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val doc = fetchDocument(source.baseUrl)

        // Latest updates: .box > .uta containers
        val latestChapters = doc.select(".box").take(40).mapNotNull { box ->
            val imgEl = box.selectFirst(".imgu img") ?: return@mapNotNull null
            val linkEl = box.selectFirst(".imgu a") ?: return@mapNotNull null
            val titleEl = box.selectFirst(".info h3") ?: return@mapNotNull null
            val chapterEl = box.selectFirst(".info ul li a.new, .info ul li:first-child a")

            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/')
            val mangaId = "olympus_$slug"
            // Confirmed from real HTML: <a class="new" href="/series/{slug}/{N}">
            // Chapter URL is already embedded in the link — do NOT reconstruct it.
            val chapterHref = chapterEl?.attr("abs:href")
                ?.ifEmpty { chapterEl.attr("href").absoluteUrl() } ?: ""
            val chapterNumber = chapterHref.trimEnd('/').substringAfterLast("/")
                .toFloatOrNull()
                ?: chapterEl?.text()?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull()
                ?: 0f
            val timeText = box.selectFirst(".info ul li .post-on, .info .post-on")?.text()
                ?.cleanText() ?: ""
            val isPaid = box.selectFirst(".fa-lock") != null

            LatestChapterItem(
                mangaId = mangaId,
                mangaSlug = slug,
                mangaTitle = titleEl.text().cleanText(),
                coverUrl = imgEl.attr("abs:src").ifEmpty { imgEl.attr("src").absoluteUrl() },
                chapterNumber = chapterNumber,
                chapterUrl = chapterHref,
                timeAgo = timeText,
                source = source,
                isNew = timeText.contains("ساعة") || timeText.contains("hour")
            )
        }

        // Popular manga: .popular-manga .entry-box
        val popular = doc.select(".popular-manga .entry-box, .entry-box.entry-box-1").take(10)
            .mapNotNull { box ->
                val img = box.selectFirst("img.best-img") ?: return@mapNotNull null
                val titleEl = box.selectFirst(".entry-title a, h3 a") ?: return@mapNotNull null
                val linkEl = box.selectFirst("a.box, .entry-image a") ?: return@mapNotNull null

                val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
                val slug = href.substringAfterLast("/series/").trimEnd('/')
                MangaItem(
                    id = "olympus_$slug",
                    slug = slug,
                    title = titleEl.text().cleanText(),
                    coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() },
                    source = source,
                    url = href
                )
            }

        HomeData(
            featured = popular.take(5),
            latestChapters = latestChapters,
            trending = popular
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/series/$slug"
        val doc = fetchDocument(url)

        val coverUrl = doc.selectFirst("img[alt=\"Manga Image\"]")
            ?.attr("abs:src")
            ?: doc.selectFirst(".text-right img.shadow-sm, .shadow-sm")?.attr("abs:src")
            ?: ""

        // Title: h1 is the most reliable; .col-md-9 doesn't exist in real HTML
        val title = doc.selectFirst("h1")?.text()?.cleanText()
            ?: doc.selectFirst(".author-info-title h6, .title")?.text()?.cleanText()
            ?: slug

        // Genres: confirmed selector from real HTML — a.subtitle inside .review-author-info
        val genres = doc.select(".review-author-info a.subtitle").map { it.text().cleanText() }

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
            val dateStr = card.selectFirst(".chapter-date span")?.text()?.cleanText()
            val dateTimestamp = card.attr("data-date").toLongOrNull()?.times(1000L)
            val isPaid = card.selectFirst(".fa-lock") != null

            Chapter(
                id = "${slug}_$chapterNum",
                mangaId = "olympus_$slug",
                number = chapterNum,
                title = chapterTitle,
                url = chapterHref,
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
        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)
                ?.replace(",", "")
        }

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
            description = description,
            genres = genres,
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
            extraHeaders = mapOf("Referer" to source.baseUrl + "/")
        )

        // Pages: .reading-content .page-break img.manga-chapter-img
        val pages = doc.select(".reading-content .page-break img.manga-chapter-img, .reading-content img")
            .mapIndexed { index, img ->
                val src = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() }
                ChapterPage(
                    index = index,
                    url = src,
                    headers = mapOf("Referer" to source.baseUrl + "/")
                )
            }

        pages
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${source.baseUrl}/ajax/search?keyword=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BaseScraperImpl.USER_AGENT)
            .header("Accept", "*/*")
            .header("Referer", source.baseUrl + "/series")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()

        val doc = Jsoup.parse(body)
        doc.select("a[href*=\"/series/\"]").mapNotNull { a ->
            val href = a.attr("abs:href").ifEmpty { a.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/')
            if (slug.isEmpty()) return@mapNotNull null
            val title = a.selectFirst("h4")?.text()?.cleanText() ?: return@mapNotNull null
            val coverUrl = a.selectFirst("img")?.attr("abs:src")
                ?.ifEmpty { a.selectFirst("img")?.attr("src")?.absoluteUrl() } ?: ""
            MangaItem(
                id = "olympus_$slug", slug = slug, title = title,
                coverUrl = coverUrl, source = source, url = href
            )
        }
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
        val url = "${source.baseUrl}/series?genre=$encodedGenre&page=$page"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument(source.baseUrl)
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
        val doc = fetchDocument("${source.baseUrl}/series")
        doc.select("a.subtitle[href*=\"genre\"]")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun parseMangaGrid(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select(".box").mapNotNull { box ->
            val imgEl = box.selectFirst(".imgu img") ?: return@mapNotNull null
            val linkEl = box.selectFirst(".imgu a") ?: return@mapNotNull null
            val titleEl = box.selectFirst(".info h3") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.substringAfterLast("/series/").trimEnd('/')
            val chapterEl = box.selectFirst(".info ul li:first-child a")
            MangaItem(
                id = "olympus_$slug", slug = slug,
                title = titleEl.text().cleanText(),
                coverUrl = imgEl.attr("abs:src").ifEmpty { imgEl.attr("src").absoluteUrl() },
                source = source,
                latestChapter = chapterEl?.text()?.replace("[^0-9]".toRegex(), "")?.toIntOrNull(),
                url = href
            )
        }
    }
}
