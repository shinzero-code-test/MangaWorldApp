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

        val coverUrl = doc.selectFirst(".manga-poster img, .header-bg img")
            ?.let { it.attr("abs:src").ifEmpty { it.attr("data-src").absoluteUrl() } } ?: ""

        val title = doc.selectFirst(".manga-title-large, .manga-title, h1")?.text()?.cleanText() ?: slug

        val description = doc.selectFirst(".manga-synopsis, .manga-description, .manga-info-header + div")
            ?.text()?.cleanText() ?: ""

        val statusText = doc.selectFirst(".badge.status, .badge.type")?.text()?.cleanText()
        val status = MangaStatus.from(statusText)

        val genres = doc.select(".genre-tag, .badge:not(.status):not(.type)")
            .map { it.text().cleanText() }
            .filter { it.length in 2..20 }
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

        // Try to get chapters from the chapters-list (may be AJAX-loaded)
        val chapters = doc.select(".chapters-list .chapter-row, .chapters-list a[href*='/manga/']")
            .mapNotNull { el ->
                val chLink = el.selectFirst("a[href]") ?: el.takeIf { el.tagName() == "a" } ?: return@mapNotNull null
                val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
                val chText = chLink.text().cleanText()
                val chNum = chText.replace("الفصل", "").replace("[^0-9.]".toRegex(), "").trim().toFloatOrNull()
                    ?: chHref.trimEnd('/').substringAfterLast("/").toFloatOrNull()
                    ?: return@mapNotNull null
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

        doc.select("img[data-src], img[src*='wp-content'], .reading-content img, .page-break img")
            .mapNotNull { img ->
                val src = img.attr("data-src").ifEmpty {
                    img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() }
                }.encodeForUrl()
                src.takeIf { it.isNotBlank() && !it.contains("logo") && !it.contains("avatar") }
            }
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
            }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "${source.baseUrl}/?s=$encoded" else "${source.baseUrl}/page/$page/?s=$encoded"
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
