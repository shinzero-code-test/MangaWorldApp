package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
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
        val rawHtml = fetchRawHtml(url)

        val meta = linkedMapOf<String, String>()
        doc.select(".flex.justify-between.items-center").forEach { row ->
            val values = row.select("span, a")
                .map { it.text().cleanText() }
                .filter { it.isNotBlank() }
            if (values.size >= 2) meta[values.first()] = values.last()
        }

        val props = extractIslandProps(rawHtml, "MangaChaptersLoader")
        val manga = decodeWire(props?.opt("manga")) as? Map<*, *>

        val title = decodeStr(manga?.get("title")).ifBlank {
            doc.selectFirst("h1")?.text()?.cleanText().orEmpty()
        }.ifBlank { slug }

        val coverUrl = decodeStr(manga?.get("cover_image")).encodeForUrl().ifBlank {
            doc.select("img[src*='/covers/'], img[data-src*='/covers/']")
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
        }

        val description = decodeStr(manga?.get("description")).cleanText().ifBlank {
            extractDescription(doc)
        }

        val genres = decodeList(manga?.get("Tags"))
            .mapNotNull { tag ->
                val tagMap = tag as? Map<*, *> ?: return@mapNotNull null
                decodeStr(tagMap["name"]).cleanText().ifBlank { null }
            }
            .ifEmpty {
                doc.select("a[href*='genres=']")
                    .map { it.text().cleanText().removeSuffix(" مانجا تصفح") }
                    .filter { it.isNotBlank() }
            }
            .distinct()

        val chapters = decodeList(manga?.get("MangaChapters"))
            .mapNotNull { chapter ->
                val chapterMap = chapter as? Map<*, *> ?: return@mapNotNull null
                val chapterId = decodeLong(chapterMap["id"]) ?: return@mapNotNull null
                val chapterText = decodeStr(chapterMap["chapter_number"]).ifBlank {
                    decodeStr(chapterMap["title"])
                }
                val chapterNumber = parseChapterNumber(chapterText) ?: return@mapNotNull null
                val chapterPath = normalizeChapterPath(chapterText, chapterNumber)
                val createdAt = decodeStr(chapterMap["created_at"])
                val dateLong = runCatching {
                    java.time.Instant.parse(createdAt).toEpochMilli()
                }.getOrNull()

                Chapter(
                    id = "${slug}_$chapterId",
                    mangaId = "mangasid_$slug",
                    number = chapterNumber,
                    title = decodeStr(chapterMap["title"]).cleanText().takeIf {
                        it.isNotBlank() && !it.equals("Chapter $chapterPath", ignoreCase = true)
                    },
                    url = "${source.baseUrl}/reader/$slug/$chapterPath",
                    date = dateLong,
                    dateText = createdAt.substringBefore('T').takeIf { it.isNotBlank() },
                    isPaid = decodeLong(chapterMap["price"])?.let { it > 0 } == true
                )
            }
            .ifEmpty {
                doc.select("a[href^='/reader/']")
                    .mapNotNull { link -> parseVisibleChapter(slug, link.attr("abs:href").ifEmpty { link.attr("href").absoluteUrl() }, link.text().cleanText()) }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.number }

        MangaDetail(
            id = "mangasid_$slug",
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = source,
            description = description,
            genres = genres,
            status = MangaStatus.from(decodeStr(manga?.get("status")).ifBlank { meta["الحالة"].orEmpty() }),
            type = MangaType.from(genres.joinToString(" ")),
            totalChapters = doc.selectFirst("meta[name='manga:chapters']")?.attr("content")?.toIntOrNull()
                ?: chapters.size,
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

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val sort = when (sortBy) {
            SortBy.POPULARITY -> "views"
            SortBy.OLDEST -> "title&sortOrder=ASC"
            else -> "latest"
        }
        val params = mutableListOf("page=$page", "sort=$sort")
        genre?.takeIf { it.isNotBlank() }?.let { params += "genres=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val items = parseCards(fetchDocument("${source.baseUrl}/manga-list?${params.joinToString("&")}")).map { it.first }
        items.filter { status == null || it.status == status || it.status == MangaStatus.UNKNOWN }
            .filter { type == null || it.type == type || it.type == MangaType.UNKNOWN }
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
            val rating = card.selectFirst(".absolute.bottom-0 .fa-star")
                ?.parent()
                ?.selectFirst("span")
                ?.text()
                ?.cleanText()
                ?.toFloatOrNull()
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

    private suspend fun fetchRawHtml(url: String): String = withContext(Dispatchers.IO) {
        val cookies = getCookiesForDomain(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "ar,en;q=0.9")
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        body
    }

    private fun extractIslandProps(rawHtml: String, componentToken: String): JSONObject? {
        var pos = 0
        while (true) {
            val tagStart = rawHtml.indexOf("<astro-island", pos)
            if (tagStart < 0) return null
            val tagEnd = findOpenTagEnd(rawHtml, tagStart)
            if (tagEnd < 0) return null
            pos = tagEnd + 1

            val tagContent = rawHtml.substring(tagStart, tagEnd + 1)
            val componentUrl = extractAttrValue(tagContent, "component-url") ?: continue
            if (!componentUrl.contains(componentToken)) continue
            val propsValue = extractAttrValue(tagContent, "props") ?: continue
            return runCatching { JSONObject(propsValue.htmlUnesc()) }.getOrNull()
        }
    }

    private fun findOpenTagEnd(html: String, start: Int): Int {
        var index = start
        var inQuote = false
        var quoteChar = ' '
        while (index < html.length) {
            val char = html[index]
            when {
                inQuote -> if (char == quoteChar) inQuote = false
                char == '"' || char == '\'' -> {
                    inQuote = true
                    quoteChar = char
                }
                char == '>' -> return index
            }
            index++
        }
        return -1
    }

    private fun extractAttrValue(tagContent: String, name: String): String? {
        val marker = "$name=\""
        val start = tagContent.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = tagContent.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return tagContent.substring(valueStart, valueEnd)
    }

    private fun String.htmlUnesc(): String = replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")

    private fun decodeWire(value: Any?): Any? {
        return when {
            value is JSONArray && value.length() == 2 -> when (value.getInt(0)) {
                0 -> {
                    val inner = value.get(1)
                    when {
                        inner == JSONObject.NULL -> null
                        inner is JSONObject -> decodeWire(inner)
                        inner is JSONArray -> decodeWire(inner)
                        else -> inner
                    }
                }

                1 -> {
                    val innerArray = value.getJSONArray(1)
                    (0 until innerArray.length()).map { decodeWire(innerArray.get(it)) }
                }

                else -> value
            }

            value is JSONObject -> value.keys().asSequence().associateWith { key ->
                decodeWire(value.opt(key))
            }

            else -> value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeList(value: Any?): List<Any?> = decodeWire(value) as? List<Any?> ?: emptyList()

    private fun decodeStr(value: Any?): String = decodeWire(value)?.toString().orEmpty()

    private fun decodeLong(value: Any?): Long? = (decodeWire(value) as? Number)?.toLong()

    private fun extractDescription(doc: Document): String {
        val heading = doc.select("h3").firstOrNull { it.text().contains("نبذة عن العمل") }
        return sequenceOf(
            heading?.nextElementSibling(),
            heading?.parent()?.selectFirst("p"),
            doc.selectFirst("meta[name=description]")
        )
            .mapNotNull { element ->
                element?.text()?.cleanText()?.ifBlank { element.attr("content").cleanText() }
            }
            .firstOrNull { it.length > 20 }
            .orEmpty()
    }

    private fun parseVisibleChapter(slug: String, href: String, text: String): Chapter? {
        if (!href.contains("/reader/")) return null
        val chapterNumber = parseChapterNumber(href.substringAfterLast('/').substringBefore('?'))
            ?: parseChapterNumber(text)
            ?: return null
        return Chapter(
            id = "${slug}_${href.hashCode()}",
            mangaId = "mangasid_$slug",
            number = chapterNumber,
            title = text.ifBlank { null },
            url = href
        )
    }

    private fun normalizeChapterPath(chapterText: String, chapterNumber: Float): String {
        val raw = chapterText.trim()
        if (raw.matches(Regex("\\d+(?:\\.\\d+)?"))) return raw
        return if (chapterNumber == chapterNumber.toInt().toFloat()) {
            chapterNumber.toInt().toString()
        } else {
            chapterNumber.toString()
        }
    }

    private fun parseChapterNumber(text: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
}
