package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
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
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.AREASCANS, settingsRepo) {

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/browse/")
        val mangaCards = parseMangaCards(doc)

        HomeData(
            featured = mangaCards.take(8),
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
        val chapters = doc.select(".chapters-list a.chapter-item, .chapters-list a[href], .chapters-list .chapter-row")
            .mapNotNull { el ->
                val chLink = el.takeIf { el.tagName() == "a" }
                    ?: el.selectFirst("a[href]")
                    ?: return@mapNotNull null
                val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
                val chText = chLink.selectFirst(".chap-num")?.text()?.cleanText()
                    ?: chLink.text().cleanText()
                val chNum = chText.replace("الفصل", "").replace("[^0-9.]".toRegex(), "").trim().toFloatOrNull()
                    ?: chHref.trimEnd('/').substringAfterLast("/").substringBefore("?").toFloatOrNull()
                    ?: return@mapNotNull null
                val dateText = chLink.selectFirst(".chap-date")?.text()?.cleanText()
                Chapter(
                    id = "${slug}_$chNum",
                    mangaId = "${source.id}_$slug",
                    number = chNum,
                    title = chText.replace("الفصل", "").trim().ifBlank { null },
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

        // ar.kenmanga.com uses #reader-canvas for chapter images
        doc.select(
            "#reader-canvas img, .reading-content img, .page-break img, img.wp-manga-chapter-img"
        )
            .mapNotNull { img ->
                val dataSrc = img.attr("data-src").ifEmpty { null }
                val src = img.attr("abs:src").ifEmpty { null }
                val actualSrc = dataSrc ?: src
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
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "${source.baseUrl}/?s=$encoded&post_type=wp-manga" else "${source.baseUrl}/page/$page/?s=$encoded&post_type=wp-manga"
        val doc = fetchDocument(url)
        parseMangaCards(doc)
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
}
