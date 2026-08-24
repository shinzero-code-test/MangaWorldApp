package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Scraper for rocksmanga.com — Custom "wp-fire" theme with Madara Core.
 *
 * Uses standard Madara AJAX endpoints but has different HTML structure.
 * Key selectors:
 *   List: div.unit in div.original.card-lg
 *   Detail: aside.content div.info
 *   Chapters: ul.scroll-sm li.item
 *   Pages: div#ch-images div.page img.preload-image (data-src)
 */
class RockMangaScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.ROCKMANGA, settingsRepo) {

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument(source.baseUrl)
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

        val coverUrl = doc.selectFirst("aside.content div.poster img, #manga-page img")
            ?.let { it.attr("abs:src").ifEmpty { it.attr("data-src").absoluteUrl() } } ?: ""

        val title = doc.selectFirst("aside.content div.info h1")?.text()?.cleanText() ?: slug

        val description = doc.selectFirst("aside.content div.description")?.text()?.cleanText() ?: ""

        val statusText = doc.selectFirst("aside.content div.info p")?.text()?.cleanText()
        val status = MangaStatus.from(statusText)

        val genres = doc.select("div.meta div a").map { it.text().cleanText() }
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

        val chapters = doc.select("ul.scroll-sm li.item").mapNotNull { li ->
            val chLink = li.selectFirst("a[href]") ?: return@mapNotNull null
            val chHref = chLink.attr("abs:href").ifEmpty { chLink.attr("href").absoluteUrl() }
            val chText = chLink.selectFirst("span.contain-zeb")?.text()?.cleanText()
                ?: chLink.attr("title").cleanText()
            val chNum = ScraperText.firstChapterNumber(chText)
                ?: ScraperText.lastSegmentNumber(chHref)
                ?: return@mapNotNull null
            val dateText = li.selectFirst("span.time")?.text()?.cleanText()
            val dateLong = dateText?.let { parseArabicDate(it) }
            val scanlator = li.selectFirst("span.username span")?.text()?.cleanText()
            Chapter(
                id = "${slug}_$chNum",
                mangaId = "rockmanga_$slug",
                number = chNum,
                title = chText.replace("الفصل", "").trim().ifBlank { null },
                url = chHref,
                date = dateLong,
                dateText = dateText
            )
        }.distinctBy { it.url }.sortedByDescending { it.number }

        val viewsText = ScraperText.extractViews(doc.body().text())?.toString()

        MangaDetail(
            id = "rockmanga_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
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

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl, extraHeaders = mapOf("Referer" to source.baseUrl + "/"))

        doc.select("div#ch-images div.page img.preload-image, div#ch-images img")
            .mapNotNull { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("abs:src") }.ifEmpty { img.attr("src").absoluteUrl() }
                    .encodeForUrl()
                src.takeIf { it.isNotBlank() }
            }
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(index = index, url = src, headers = buildImageHeaders(src, chapterUrl))
            }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = fetchDocument("${source.baseUrl}/?post_type=wp-manga&s=$encoded&page=$page")
        parseMangaCards(doc)
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        // Path position: encode with %20 (URLEncoder's "+" is wrong inside paths).
        val enc = java.net.URLEncoder.encode(genre, "UTF-8").replace("+", "%20")
        val doc = fetchDocument("${source.baseUrl}/manga-genre/$enc/page/$page/")
        parseMangaCards(doc)
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/manga/?sort=most_viewed")
        parseMangaCards(doc)
    }

    override suspend fun browseManga(
        page: Int, genre: String?, status: MangaStatus?, type: MangaType?, sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val sort = when (sortBy) {
            SortBy.POPULARITY -> "most_viewed"
            SortBy.RATING -> "rating"
            SortBy.OLDEST -> "title_az"
            SortBy.LATEST -> "recently_added"
        }
        val params = mutableListOf("sort=$sort", "page=$page")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genre=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val doc = fetchDocument("${source.baseUrl}/manga/?${params.joinToString("&")}")
        parseMangaCards(doc)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/manga/")
        doc.select("a[href*='/manga-genre/']")
            .map { it.text().cleanText() }
            .filter { it.length in 2..20 }
            .distinct()
    }

    private fun parseMangaCards(doc: org.jsoup.nodes.Document): List<MangaItem> {
        return doc.select("div.unit, div.original.card-lg div.unit").mapNotNull { unit ->
            val linkEl = unit.selectFirst("a.poster[href*='/manga/'], a[href*='/manga/']") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href").absoluteUrl() }
            val slug = href.trimEnd('/').substringAfterLast("/manga/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null
            val img = unit.selectFirst("a.poster img, img")
            val title = unit.selectFirst("div.info > a")?.text()?.cleanText() ?: slug
            val coverUrl = img?.let {
                it.attr("abs:src").ifEmpty { it.attr("data-src").absoluteUrl() }
            }.orEmpty()
            val genres = unit.select("div.info ul.content li a").map { it.text().cleanText() }
            MangaItem(
                id = "rockmanga_$slug",
                slug = slug,
                title = title,
                coverUrl = coverUrl.encodeForUrl(),
                source = source,
                genres = genres,
                url = href
            )
        }.distinctBy { it.id }
    }

    private fun parseArabicDate(text: String): Long? = ScraperText.parseArabicDate(text)
}
