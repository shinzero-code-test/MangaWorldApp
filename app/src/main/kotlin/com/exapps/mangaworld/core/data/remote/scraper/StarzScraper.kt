package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Scraper for manga-starz.net (Manga Starz) — WordPress + Madara Theme
 *
 * CSS Selectors (from HTML analysis):
 * HOME:
 *   Card container  → div.page-item-detail.manga
 *   Cover link+img  → .item-thumb.c-image-hover a img.img-responsive
 *   Manga title     → .item-summary .post-title a
 *   Manga link      → .item-thumb.c-image-hover a [href]
 *   post-id         → .item-thumb.c-image-hover [data-post-id]
 *   Chapter items   → .chapter-item (inside each card)
 *   Chapter link    → .chapter-item .chapter.font-meta a.btn-link
 *   Chapter time    → .chapter-item .post-on
 *   Popular widget  → .widget-manga-recent .popular-item-wrap
 *
 * DETAIL:
 *   Cover           → .summary_image img
 *   Title           → .post-title h1
 *   Alternative names → .post-content_item .summary-content (index 1)
 *   Status          → .post-content_item:nth-child(3) .summary-content
 *   Description     → .description-summary p, .summary__content
 *   Genres          → .genres-content a (if present)
 *   Chapter list    → .listing-chapters_wrap li
 *   Chapter link    → .listing-chapters_wrap li a [href]
 *   Chapter title   → .listing-chapters_wrap li a text
 *   Chapter date    → .chapter-release-date i, .chapter-release-date span
 *
 * CHAPTER:
 *   Pages           → .reading-content img, .page-break img
 *   Prev chapter    → a.prev-chapter [href]
 *   Next chapter    → a.next-chapter [href]
 *   Chapter select  → select.single-chapter-select option [data-redirect] or [value]
 *
 * URL Patterns:
 *   Home     → /
 *   Manga    → /manga/{slug}/
 *   Chapter  → /manga/{slug}/{chapter_number}/
 *   Browse   → /manga/?genre={slug}
 *   Search   → /?s={query}&post_type=wp-manga
 *   AJAX     → /wp-admin/admin-ajax.php (POST, for full chapter list)
 */
class StarzScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.STARZ, settingsRepo) {

    // ─── Home ─────────────────────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument(source.baseUrl)

        // Latest: .page-item-detail.manga cards
        val latestItems = mutableListOf<LatestChapterItem>()
        val mangaCards = mutableListOf<MangaItem>()

        doc.select("div.page-item-detail.manga").forEach { card ->
            val imgEl = card.selectFirst(".item-thumb img.img-responsive") ?: return@forEach
            val linkEl = card.selectFirst(".item-thumb a") ?: return@forEach
            val titleEl = card.selectFirst(".post-title a, .post-title h3 a, .item-summary .post-title a")

            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            if (slug.isEmpty()) return@forEach

            val coverUrl = imgEl.attr("abs:src").ifEmpty {
                (imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }).absoluteUrl()
            }
            val title = titleEl?.text()?.cleanText() ?: slug
            val mangaId = "starz_$slug"

            mangaCards.add(
                MangaItem(
                    id = mangaId, slug = slug, title = title,
                    coverUrl = coverUrl, source = source, url = href
                )
            )

            // Confirmed from real HTML: chapters use <a class="btn-link" href="…/manga/{slug}/{N}/">
            // .chapter-item does NOT exist — replaced by direct a.btn-link links
            card.select("a.btn-link[href*='/manga/']").take(2).forEach chapters@{ chLink ->
                val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
                val chNum = chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                    ?: chLink.text().replace("[^0-9.]".toRegex(), "").trim().toFloatOrNull()
                    ?: return@chapters
                val isNew = false   // no .c-new-tag at this level in real HTML

                latestItems.add(
                    LatestChapterItem(
                        mangaId = mangaId, mangaSlug = slug, mangaTitle = title,
                        coverUrl = coverUrl, chapterNumber = chNum, chapterUrl = chHref,
                        timeAgo = "", source = source, isNew = isNew
                    )
                )
            }
        }

        // Popular: widget section
        val popular = doc.select(".widget-manga-recent .popular-item-wrap").mapNotNull { item ->
            val img = item.selectFirst(".popular-img img") ?: return@mapNotNull null
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val title = item.selectFirst(".post-title a, .popular-title")?.text()?.cleanText()
                ?: link.attr("title")
            val href = link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            MangaItem(
                id = "starz_$slug", slug = slug, title = title,
                coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() },
                source = source, url = href
            )
        }

        HomeData(
            featured = popular.ifEmpty { mangaCards }.take(8),
            latestChapters = latestItems.take(30),
            trending = popular.ifEmpty { mangaCards }.take(20)
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/manga/$slug/"
        val doc = fetchDocument(url)

        val coverUrl = doc.selectFirst(".summary_image img, .profile-manga img")
            ?.let { img ->
                img.attr("abs:src").ifEmpty {
                    (img.attr("data-src").ifEmpty { img.attr("src") }).absoluteUrl()
                }
            } ?: ""

        val title = doc.selectFirst(".post-title h1")?.text()?.cleanText()
            ?: doc.selectFirst("h1.entry-title, .manga-title")?.text()?.cleanText()
            ?: slug

        val description = doc.selectFirst(".description-summary p, .summary__content p")
            ?.text()?.cleanText()
            ?: doc.selectFirst(".description-summary, .summary__content")?.text()?.cleanText()
            ?: ""

        // Status from post-content_item
        val statusEl = doc.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading")?.text()?.contains("الحالة") == true ||
            item.selectFirst(".summary-heading")?.text()?.contains("Status") == true
        }
        val statusText = statusEl?.selectFirst(".summary-content")?.text()?.cleanText()
        val status = MangaStatus.from(statusText)

        // Genres: Madara/WP-Manga confirmed structure — .genres-content a exists in this theme version
        // Fallback: .post-content_item containing "التصنيف" heading
        val genres = doc.select(".genres-content a, .c-cat-list a").map { it.text().cleanText() }
            .ifEmpty {
                doc.select(".post-content_item").firstOrNull { item ->
                    item.selectFirst(".summary-heading")?.text()?.let {
                        it.contains("التصنيف") || it.contains("Genre") || it.contains("الفئات")
                    } == true
                }?.select(".summary-content a")?.map { it.text().cleanText() }
                    ?: emptyList()
            }
            .filter { it.isNotBlank() }

        // Type from page body text
        val bodyText = doc.body().text()
        val type = MangaType.from(
            when {
                bodyText.contains("manhwa", true) || bodyText.contains("مانهوا", true) -> "manhwa"
                bodyText.contains("manhua", true) -> "manhua"
                bodyText.contains("manga", true) || bodyText.contains("مانجا", true) -> "manga"
                else -> null
            }
        )

        fun parseChapterTitle(fullText: String): String? {
            val cleaned = fullText.replace(Regex("الفصل\\s*[0-9.,]+\\s*[:.]*\\s*"), "").trim()
            return cleaned.ifEmpty { null }
        }

        fun parseChapterDate(dateEl: org.jsoup.nodes.Element?): Pair<String?, Long?> {
            val dateText = dateEl?.text()?.cleanText() ?: return null to null
            val dateLong = try {
                val datetime = dateEl.attr("datetime")
                if (datetime.isNotBlank()) java.time.Instant.parse(datetime).toEpochMilli()
                else null
            } catch (e: Exception) { null }
            return dateText to dateLong
        }

        // Chapter list: .listing-chapters_wrap li
        val listChapters = doc.select(".listing-chapters_wrap li").mapNotNull { li ->
            val chLink = li.selectFirst("a[href]") ?: return@mapNotNull null
            val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
            val chText = chLink.text().cleanText()

            // Extract chapter number: "الفصل 18" or just "18"
            val chNumStr = chText.replace("الفصل", "").replace("[^0-9.]".toRegex(), "").trim()
            val chNum = chNumStr.toFloatOrNull()
                ?: chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                ?: return@mapNotNull null

            // Date: .chapter-release-date
            val dateEl = li.selectFirst(".chapter-release-date i, .chapter-release-date span")
            val (dateText, dateLong) = parseChapterDate(dateEl)

            Chapter(
                id = "${slug}_$chNum",
                mangaId = "starz_$slug",
                number = chNum,
                title = parseChapterTitle(chText),
                url = chHref,
                date = dateLong,
                dateText = dateText
            )
        }

        val optionChapters = doc.select("select.single-chapter-select option[data-redirect], select.single-chapter-select option[value]")
            .mapNotNull { opt ->
                val hrefRaw = opt.attr("data-redirect").ifBlank { opt.attr("value") }
                if (hrefRaw.isBlank() || hrefRaw == "#") return@mapNotNull null
                val href = if (hrefRaw.startsWith("http")) hrefRaw else hrefRaw.absoluteUrl()
                val text = opt.text().cleanText()
                val num = text.replace("الفصل", "").replace("[^0-9.]".toRegex(), "").toFloatOrNull()
                    ?: href.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                    ?: return@mapNotNull null
                Chapter(
                    id = "${slug}_$num",
                    mangaId = "starz_$slug",
                    number = num,
                    title = parseChapterTitle(text),
                    url = href
                )
            }

        // Try AJAX for full chapter list (Madara admin-ajax endpoint)
        val ajaxChapters = try {
            val postId = doc.selectFirst("input.rating-post-id")?.attr("value")
                ?: doc.selectFirst("body")?.let { body ->
                    body.classNames().firstOrNull { it.startsWith("postid-") }
                        ?.removePrefix("postid-")
                } ?: ""

            if (postId.isNotBlank()) {
                // Madara theme action (confirmed from manga-single.js source)
                // Try both common variants
                var chapters = emptyList<Chapter>()
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
                        .header("Referer", url)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(formBody)
                        .build()

                    val response = client.newCall(ajaxRequest).execute()
                    val bodyStr = response.body?.string() ?: "{}"
                    response.close()

                    val json = JSONObject(bodyStr)
                    if (json.optBoolean("success", false)) {
                        val html = json.optString("data", "")
                        val chapDoc = Jsoup.parse(html)
                        chapters = chapDoc.select("li").mapNotNull { li ->
                            val chLink = li.selectFirst("a[href]") ?: return@mapNotNull null
                            val chHref = chLink.attr("abs:href").ifEmpty {
                                chLink.attr("href").absoluteUrl()
                            }
                            val chText = chLink.text().cleanText()
                            val chNumStr = chText.replace("الفصل", "").replace("[^0-9.]".toRegex(), "").trim()
                            val chNum = chNumStr.toFloatOrNull()
                                ?: chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                                ?: return@mapNotNull null
                            val dateEl = li.selectFirst(".chapter-release-date i, .chapter-release-date span")
                            val (dateText, dateLong) = parseChapterDate(dateEl)
                            Chapter(
                                id = "${slug}_$chNum",
                                mangaId = "starz_$slug",
                                number = chNum,
                                title = parseChapterTitle(chText),
                                url = chHref,
                                date = dateLong,
                                dateText = dateText
                            )
                        }
                        if (chapters.isNotEmpty()) break   // success — stop trying actions
                    }
                }
                chapters
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Views from post-content_item or page body
        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)
                ?.replace(",", "")
        }

        val chapters = (listChapters + optionChapters + ajaxChapters)
            .distinctBy { it.url }
            .sortedByDescending { it.number }

        MangaDetail(
            id = "starz_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            description = description,
            genres = genres,
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
        val doc = fetchDocument(
            chapterUrl,
            extraHeaders = mapOf("Referer" to source.baseUrl + "/")
        )

        // .reading-content img or .page-break img
        val pages = doc.select(".reading-content img[src], .page-break img[src]")
            .filter { it.attr("src").isNotEmpty() }
            .mapIndexed { index, img ->
                val src = img.attr("abs:src").ifEmpty {
                    (img.attr("data-src").ifEmpty { img.attr("src") }).absoluteUrl()
                }
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
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "${source.baseUrl}/?s=$encoded&post_type=wp-manga&paged=$page"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}/manga/?genre=$genre&page=$page"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val url = "${source.baseUrl}/manga/?m_orderby=views"
        val doc = fetchDocument(url)
        parseMangaGrid(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/manga/")
        doc.select("ul.genre-scroll-list li a, .genres-content a")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun parseMangaGrid(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select(
            "div.page-item-detail.manga, div.c-tabs-item__content, div.row.c-tabs-item__content, div.c-image-hover"
        ).mapNotNull { card ->
            val imgEl = card.selectFirst("img.img-responsive, .item-thumb img, img") ?: return@mapNotNull null
            val linkEl = card.selectFirst(".post-title a[href], .item-thumb a[href], a[href*=\"/manga/\"]") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            if (slug.isEmpty()) return@mapNotNull null
            val titleEl = card.selectFirst(".post-title a, h3 a, [class*=\"post-title\"] a")
            val title = titleEl?.text()?.cleanText()
                ?: linkEl.attr("title").cleanText()
            if (title.isEmpty()) return@mapNotNull null
            MangaItem(
                id = "starz_$slug", slug = slug, title = title,
                coverUrl = imgEl.attr("abs:src").ifEmpty {
                    (imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }).absoluteUrl()
                },
                source = source, url = href
            )
        }
    }
}
