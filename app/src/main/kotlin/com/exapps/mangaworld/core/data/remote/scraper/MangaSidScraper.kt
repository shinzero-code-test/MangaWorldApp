package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.Chapter
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject

class MangaSidScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.MANGASID, settingsRepo) {

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/manga-list")
        val cards = parseCards(doc)
        HomeData(
            featured = cards.map { it.first }.take(8),
            latestChapters = cards.mapNotNull { it.second }.take(24),
            trending = cards.map { it.first }.take(20)
        )
    }

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/manga/$slug"
        val doc = fetchDocument(url)

        val title = doc.selectFirst("h1")?.text()?.cleanText().orEmpty().ifBlank { slug }
        val coverUrl = doc.select("img[src*='/covers/'], img[data-src*='/covers/']")
            .firstOrNull { img ->
                img.attr("alt").contains(title, ignoreCase = true) ||
                    img.attr("alt").contains("غلاف مانجا")
            }
            ?.let { img ->
                img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty { img.attr("src") }.absoluteUrl()
                }
            }
            ?.encodeForUrl()
            .orEmpty()

        val description = extractDescription(doc)
        val genres = doc.select("a[href*='genres=']")
            .map { it.text().cleanText().removeSuffix(" مانجا تصفح") }
            .filter { it.isNotBlank() }
            .distinct()

        val meta = linkedMapOf<String, String>()
        doc.select(".flex.justify-between.items-center").forEach { row ->
            val values = row.select("span, a")
                .map { it.text().cleanText() }
                .filter { it.isNotBlank() }
            if (values.size >= 2) {
                meta[values.first()] = values.last()
            }
        }

        val chapters = doc.select("a[href^='/reader/']")
            .mapNotNull { link -> parseChapter(slug, link) }
            .distinctBy { it.url }
            .sortedByDescending { it.number }

        val mergedMetaText = buildString {
            append(meta["الحالة"].orEmpty())
            append(' ')
            append(meta["التصنيف"].orEmpty())
            append(' ')
            append(genres.joinToString(" "))
        }

        MangaDetail(
            id = "mangasid_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            description = description,
            genres = genres,
            status = MangaStatus.from(meta["الحالة"]),
            type = MangaType.from(mergedMetaText),
            totalChapters = chapters.size,
            lastUpdated = meta["آخر تحديث"],
            chapters = chapters,
            url = url
        )
    }

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(
            chapterUrl,
            extraHeaders = mapOf("Referer" to source.baseUrl + "/")
        )

        doc.select(".manga-page img[src], .manga-page img[data-src], img.block.relative.z-20.w-full.h-auto")
            .mapNotNull { img ->
                val src = img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty { img.attr("src") }.absoluteUrl("https://api.mangasid.com")
                }.encodeForUrl()
                src.takeIf { it.isNotBlank() }
            }
            .distinct()
            .mapIndexed { index, src ->
                ChapterPage(
                    index = index,
                    url = src,
                    headers = buildImageHeaders(src, source.baseUrl + "/")
                )
            }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = fetchDocument("${source.baseUrl}/manga-list?search=$encoded&page=$page")
        parseCards(doc).map { it.first }
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(genre.trim(), "UTF-8")
        val doc = fetchDocument("${source.baseUrl}/manga-list?genres=$encoded&page=$page")
        parseCards(doc).map { it.first }
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val doc = fetchDocument("${source.baseUrl}/manga-list")
        parseCards(doc).map { it.first }
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        fetchDocument("${source.baseUrl}/genres")
            .select("a[href*='genres=']")
            .map { it.text().cleanText().removeSuffix(" مانجا تصفح") }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun parseCards(doc: Document): List<Pair<MangaItem, LatestChapterItem?>> {
        return doc.select(".manga-card").mapNotNull { card ->
            val coverLink = card.selectFirst("a.block.relative[href^='/manga/'], a[href^='/manga/']")
                ?: return@mapNotNull null
            val titleLink = card.selectFirst("h3 a[href^='/manga/'], a.block[href^='/manga/'][dir='auto']")
                ?: card.select("a[href^='/manga/']").lastOrNull()
                ?: return@mapNotNull null

            val href = titleLink.attr("abs:href").ifEmpty { titleLink.attr("href").absoluteUrl() }
            val slug = href.substringAfter("/manga/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null

            val image = card.selectFirst("img[src], img[data-src]")
            val coverUrl = image?.let { img ->
                img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty { img.attr("src") }.absoluteUrl()
                }
            }?.encodeForUrl().orEmpty()

            val title = titleLink.text().cleanText()
                .ifBlank { image?.attr("alt")?.cleanText().orEmpty() }
                .ifBlank { slug }

            val statusText = card.selectFirst(".absolute.bottom-0 span")?.text()?.cleanText()
            val rating = card.selectFirst(".absolute.bottom-0 .fa-star")?.parent()?.selectFirst("span")
                ?.text()?.cleanText()?.toFloatOrNull()
            val genres = card.select(".flex.flex-wrap.gap-1.justify-center.mb-2 span")
                .map { it.text().cleanText() }
                .filter { it.isNotBlank() }

            val latestLink = card.selectFirst("a[href^='/reader/']")
            val latestChapter = latestLink?.let { link ->
                val chapterHref = link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }
                val chapterNumber = parseChapterNumber(chapterHref.substringAfterLast("/").substringBefore('?'))
                    ?: parseChapterNumber(link.text())
                    ?: return@let null
                LatestChapterItem(
                    mangaId = "mangasid_$slug",
                    mangaSlug = slug,
                    mangaTitle = title,
                    coverUrl = coverUrl,
                    chapterNumber = chapterNumber,
                    chapterTitle = link.text().cleanText().ifBlank { null },
                    chapterUrl = chapterHref,
                    timeAgo = "",
                    source = source
                )
            }

            MangaItem(
                id = "mangasid_$slug",
                slug = slug,
                title = title,
                coverUrl = coverUrl,
                source = source,
                genres = genres,
                status = MangaStatus.from(statusText),
                rating = rating,
                latestChapter = latestChapter?.chapterNumber?.toInt(),
                url = href
            ) to latestChapter
        }.distinctBy { it.first.id }
    }

    private fun parseChapter(slug: String, link: Element): Chapter? {
        val href = link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }
        if (!href.contains("/reader/")) return null

        val chapterNumber = parseChapterNumber(href.substringAfterLast("/").substringBefore('?'))
            ?: parseChapterNumber(link.text())
            ?: return null

        val container = link.closest("a.manga-chapter, .manga-chapter, li, div")
        val dateText = container?.text()
            ?.let { Regex("\\d{1,2}[^0-9A-Za-z]+\\d{1,2}[^0-9A-Za-z]+\\d{4}").find(it)?.value }
            ?.cleanText()

        return Chapter(
            id = "${slug}_$chapterNumber",
            mangaId = "mangasid_$slug",
            number = chapterNumber,
            title = link.selectFirst("h3, span")?.text()?.cleanText()?.ifBlank { null }
                ?: link.text().cleanText().ifBlank { null },
            url = href,
            dateText = dateText
        )
    }

    private fun extractDescription(doc: Document): String {
        val heading = doc.select("h3").firstOrNull { it.text().contains("نبذة عن العمل") }
        return sequenceOf(
            heading?.nextElementSibling(),
            heading?.parent()?.selectFirst("p"),
            doc.selectFirst("meta[name=description]")
        )
            .mapNotNull { el ->
                when (el) {
                    null -> null
                    else -> el.text().cleanText().ifBlank { el.attr("content").cleanText() }
                }
            }
            .firstOrNull { it.length > 20 }
            .orEmpty()
    }

    private fun parseChapterNumber(text: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
}
