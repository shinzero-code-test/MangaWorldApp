package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * Scraper for azoramoon.com (Azora Moon)
 *
 * Strategy:
 *  - API endpoint  → https://api.azoramoon.com/api/query   (search / metadata)
 *  - API chapters  → https://api.azoramoon.com/api/chapters?postId={id}&page={n}&perPage=100
 *  - HTML fallback → Jsoup selectors on the SSR-rendered page
 */
class AzoraScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.AZORA, settingsRepo) {

    // ─── Raw API GET ──────────────────────────────────────────────────────────

    private fun apiGet(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", source.baseUrl + "/")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (body.isBlank() || !body.trimStart().startsWith("{")) null
        else JSONObject(body)
    }.getOrNull()

    /** Returns the first search-API post matching [term], or null. */
    private fun apiQueryFirst(term: String, perPage: Int = 3): JSONObject? {
        val enc = java.net.URLEncoder.encode(term, "UTF-8")
        val json = apiGet("https://api.azoramoon.com/api/query?searchTerm=$enc&perPage=$perPage")
        val posts = json?.optJSONArray("posts") ?: return null
        // Try exact slug match first
        for (i in 0 until posts.length()) {
            val p = posts.getJSONObject(i)
            if (p.optString("slug") == term) return p
        }
        return if (posts.length() > 0) posts.getJSONObject(0) else null
    }

    /**
     * Fetch ALL chapters for [postId] from the API, paging until done.
     * Falls back gracefully if the endpoint doesn't exist.
     */
    private fun apiGetAllChapters(postId: String, slug: String): List<Chapter> {
        val all = mutableListOf<Chapter>()
        var page = 1
        val enc = java.net.URLEncoder.encode(postId, "UTF-8")
        while (true) {
            val json = apiGet(
                "https://api.azoramoon.com/api/chapters?postId=$enc&page=$page&perPage=100"
            ) ?: break
            val arr = json.optJSONArray("chapters") ?: json.optJSONArray("data") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val num = ch.optDouble("chapterNumber", -1.0).toFloat()
                if (num < 0) continue
                val chSlug = ch.optString("chapterSlug", "").ifBlank { "chapter-${num.toInt()}" }
                val chUrl = "${source.baseUrl}/series/$slug/$chSlug"
                val dateStr = ch.optString("createdAt", "")
                val dateLong = runCatching {
                    if (dateStr.isNotBlank()) java.time.Instant.parse(dateStr).toEpochMilli() else null
                }.getOrNull()
                all.add(
                    Chapter(
                        id = "${slug}_$num",
                        mangaId = "azora_$slug",
                        number = num,
                        title = ch.optString("chapterTitle").ifBlank { null },
                        url = chUrl,
                        date = dateLong,
                        dateText = dateStr.take(10).ifBlank { null }
                    )
                )
            }
            if (arr.length() < 100) break   // last page
            page++
        }
        return all
    }

    // ─── Home ─────────────────────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val doc = fetchDocument(source.baseUrl)

        // ── Manga cards (title + cover) from every /series/ link ─────────────
        val mangaCards = mutableListOf<MangaItem>()
        doc.select("a[href^='/series/']").forEach { link ->
            val href = link.attr("href")
            if (href.contains("/chapter")) return@forEach
            val slug = href.removePrefix("/series/").trimEnd('/').substringBefore("/")
            if (slug.isEmpty() || mangaCards.any { it.slug == slug }) return@forEach
            val img = link.selectFirst("img[src]") ?: return@forEach
            val coverUrl = img.attr("src").let { if (it.startsWith("http")) it else it.absoluteUrl() }
            val title = (link.parent()?.selectFirst("h1,h2")?.text()
                ?: link.attr("title")).cleanText()
            if (title.isEmpty()) return@forEach
            mangaCards.add(
                MangaItem(
                    id = "azora_$slug", slug = slug, title = title, coverUrl = coverUrl,
                    source = source, url = "${source.baseUrl}/series/$slug"
                )
            )
        }

        // ── Featured slider ───────────────────────────────────────────────────
        val featuredItems = doc.select(
            "section.mainSlider .swiper-slide, [class*='mainSlider'] [class*='swiper-slide']"
        ).mapNotNull { slide ->
            val link = slide.selectFirst("a[href^='/series/']") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.contains("/chapter")) return@mapNotNull null
            val slug = href.removePrefix("/series/").trimEnd('/').substringBefore("/")
            if (slug.isEmpty()) return@mapNotNull null
            val img = slide.selectFirst("img[src]") ?: return@mapNotNull null
            val coverUrl = img.attr("src").let { if (it.startsWith("http")) it else it.absoluteUrl() }
            val title = (slide.selectFirst("h1,h2")?.text() ?: link.attr("title")).cleanText()
            if (title.isEmpty()) return@mapNotNull null
            MangaItem(
                id = "azora_$slug", slug = slug, title = title, coverUrl = coverUrl,
                source = source, type = MangaType.from(slide.selectFirst("span.text-xs")?.text()),
                url = "${source.baseUrl}/series/$slug"
            )
        }.distinctBy { it.id }

        // ── Latest chapters — HTML then API fallback ──────────────────────────
        val latestItems = mutableListOf<LatestChapterItem>()
        val seenUrls = mutableSetOf<String>()

        // Walk every chapter link on the home page
        doc.select("a[href*='/chapter-']").forEach { chLink ->
            val href = chLink.attr("href")
            val fullHref = if (href.startsWith("http")) href else href.absoluteUrl()
            if (!seenUrls.add(fullHref)) return@forEach

            val slugFromUrl = href.removePrefix("/series/").substringBefore("/")
            if (slugFromUrl.isEmpty()) return@forEach
            val numStr = href.substringAfterLast("chapter-").replace("[^0-9.]".toRegex(), "")
            val chapterNum = numStr.toFloatOrNull() ?: return@forEach

            // Climb DOM to find cover + time
            var container: org.jsoup.nodes.Element? = chLink.parent()
            for (i in 0..7) {
                if (container?.selectFirst("img[src]") != null) break
                container = container?.parent()
            }
            val img = container?.selectFirst("img[src]")
            val coverUrl = img?.attr("src")?.let { if (it.startsWith("http")) it else it.absoluteUrl() } ?: ""
            val timeEl = container?.selectFirst("time[datetime], time")
            val timeAgo = timeEl?.text()?.cleanText() ?: ""
            val dateLong = runCatching {
                timeEl?.attr("datetime")?.let { java.time.Instant.parse(it).toEpochMilli() }
            }.getOrNull()

            latestItems.add(
                LatestChapterItem(
                    mangaId = "azora_$slugFromUrl",
                    mangaSlug = slugFromUrl,
                    mangaTitle = mangaCards.find { it.slug == slugFromUrl }?.title ?: slugFromUrl,
                    coverUrl = coverUrl,
                    chapterNumber = chapterNum,
                    chapterTitle = null,
                    chapterUrl = fullHref,
                    timeAgo = timeAgo,
                    source = source,
                    isNew = timeAgo.contains("ساع") || timeAgo.contains("hour") ||
                            timeAgo.contains("دقيق") || timeAgo.contains("min")
                )
            )
        }

        // API fallback for latest chapters if HTML yielded nothing
        val latestFinal: List<LatestChapterItem> = if (latestItems.isNotEmpty()) latestItems else {
            runCatching {
                val json = apiGet("https://api.azoramoon.com/api/chapters/latest?perPage=30")
                    ?: apiGet("https://api.azoramoon.com/api/chapters?page=1&perPage=30")
                val arr = json?.optJSONArray("chapters") ?: json?.optJSONArray("data")
                buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val ch = arr.getJSONObject(i)
                        val slug = ch.optString("seriesSlug", "").ifBlank { ch.optString("slug", "") }
                        if (slug.isEmpty()) continue
                        val num = ch.optDouble("chapterNumber", -1.0).toFloat()
                        if (num < 0) continue
                        val chSlug = ch.optString("chapterSlug", "chapter-${num.toInt()}")
                        add(
                            LatestChapterItem(
                                mangaId = "azora_$slug", mangaSlug = slug,
                                mangaTitle = ch.optString("postTitle", slug),
                                coverUrl = ch.optString("featuredImage", ""),
                                chapterNumber = num,
                                chapterTitle = ch.optString("chapterTitle").ifBlank { null },
                                chapterUrl = "${source.baseUrl}/series/$slug/$chSlug",
                                timeAgo = ch.optString("createdAt", "").take(10),
                                source = source
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        val ratingMap = fetchRatingMap()

        HomeData(
            featured = featuredItems.ifEmpty { mangaCards.shuffled().take(8) }
                .map { it.copy(rating = it.rating ?: ratingMap[it.slug]) },
            latestChapters = latestFinal.take(30),
            trending = mangaCards.take(20).map { it.copy(rating = ratingMap[it.slug]) }
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url = "${source.baseUrl}/series/$slug"

        // ── API metadata ──────────────────────────────────────────────────────
        val apiPost = runCatching { apiQueryFirst(slug) }.getOrNull()
        val postId = apiPost?.optString("id", "")?.ifBlank { apiPost.optString("_id", "") }

        // ── HTML ──────────────────────────────────────────────────────────────
        val doc = fetchDocument(url)

        val coverUrl = apiPost?.optString("featuredImage", "")?.ifBlank { null }
            ?: doc.select("img[class*='object-cover'],img[class*='cover'],img[src*='storage']")
                .firstOrNull()?.attr("abs:src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        val title = apiPost?.optString("postTitle", "")?.cleanText()?.ifBlank { null }
            ?: doc.select("h1").maxByOrNull { it.text().length }?.text()?.cleanText()
            ?: slug

        val statusRaw = apiPost?.optString("seriesStatus", "").orEmpty()
            .ifBlank {
                doc.select("p,span,div").map { it.text() }.firstOrNull {
                    it.contains("ongoing", true) || it.contains("completed", true) ||
                            it.contains("مستمر", true) || it.contains("مكتمل", true)
                } ?: ""
            }
        val status = MangaStatus.from(statusRaw)

        val typeText = apiPost?.optString("seriesType", "").ifNullOrBlank {
            doc.select("span.text-xs,span.font-medium")
                .firstOrNull { it.text().contains("مان", true) || it.text().contains("man", true) }
                ?.text()
        }
        val type = MangaType.from(typeText)

        val genres = doc.select("a[href*='/series?genres='],a[href*='genres']")
            .map { it.text().cleanText() }.filter { it.isNotEmpty() }.distinct()

        val description = doc.select("p,div[class*='synopsis'],div[class*='description']")
            .firstOrNull { it.text().length > 40 && it.children().size < 5 }
            ?.text()?.cleanText() ?: ""

        val rating = apiPost?.optDouble("averageRating")?.takeIf { it > 0 }?.toFloat()
            ?: fetchRatingMap()[slug]

        val viewsText = Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)")
            .find(doc.body().text())?.groupValues?.get(1)?.replace(",", "")

        // ── Chapters: API → HTML A → HTML B (paginated) ───────────────────────
        var chapters: List<Chapter> = emptyList()

        if (!postId.isNullOrBlank()) {
            chapters = runCatching { apiGetAllChapters(postId, slug) }.getOrDefault(emptyList())
        }
        if (chapters.isEmpty()) {
            chapters = parseChaptersFromHtml(doc, slug)
        }

        // If HTML only gave us ≤20 and total suggests more, try "p" param pagination
        val totalFromPage = extractTotalChapters(doc)
        if (chapters.size <= 20 && (totalFromPage == null || chapters.size < totalFromPage)) {
            val map = linkedMapOf<String, Chapter>()
            chapters.forEach { map[it.url] = it }
            var p = 2
            while (p <= 50) {
                val pageDoc = runCatching { fetchDocument("$url?p=$p") }.getOrNull() ?: break
                val pChapters = parseChaptersFromHtml(pageDoc, slug)
                if (pChapters.isEmpty()) break
                var added = 0
                pChapters.forEach { if (map.putIfAbsent(it.url, it) == null) added++ }
                if (added == 0) break
                p++
            }
            if (map.size > chapters.size) chapters = map.values.toList()
        }

        val sorted = chapters.sortedByDescending { it.number }

        MangaDetail(
            id = "azora_$slug", slug = slug, title = title, coverUrl = coverUrl,
            source = source, description = description, genres = genres, status = status,
            type = type, rating = rating, views = viewsText,
            totalChapters = totalFromPage ?: sorted.size,
            chapters = sorted,
            lastUpdated = doc.selectFirst("meta[property='article:modified_time']")?.attr("content"),
            url = url
        )
    }

    // ─── Chapter Pages ────────────────────────────────────────────────────────

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl)
        var pages = doc.select(
            ".comic-images-wrapper figure img[src]," +
            ".comic-images-wrapper img[src]," +
            "[class*='reader'] img[src]"
        ).mapIndexed { idx, img ->
            val src = img.attr("src").let { if (it.startsWith("http")) it else it.absoluteUrl() }
            val ri = img.attr("data-reader-index").toIntOrNull() ?: idx
            ChapterPage(index = ri, url = src)
        }.sortedBy { it.index }

        if (pages.isEmpty())
            pages = doc.select("img[src*='WP-manga'],img[src*='storage.azora']")
                .mapIndexed { idx, img -> ChapterPage(idx, img.attr("abs:src")) }

        if (pages.isEmpty()) {
            val base = doc.selectFirst("img[src*='storage']")?.attr("src")
                ?.substringBeforeLast("/")?.plus("/")
            if (base != null) pages = (1..20).map { n -> ChapterPage(n - 1, "$base${n}.jpg") }
        }
        pages
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val json = apiGet("https://api.azoramoon.com/api/query?searchTerm=$enc&perPage=30")
            ?: return@runCatching emptyList()
        val posts = json.optJSONArray("posts") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until posts.length()) {
                val post = posts.getJSONObject(i)
                val slug = post.optString("slug", "").ifBlank { continue }
                add(
                    MangaItem(
                        id = "azora_$slug", slug = slug,
                        title = post.optString("postTitle", slug),
                        coverUrl = post.optString("featuredImage", ""),
                        source = source,
                        status = MangaStatus.from(post.optString("seriesStatus")),
                        type = MangaType.from(post.optString("seriesType")),
                        rating = post.optDouble("averageRating").takeIf { it > 0 }?.toFloat(),
                        url = "${source.baseUrl}/series/$slug"
                    )
                )
            }
        }
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        parseMangaList(fetchDocument("${source.baseUrl}/series?genres=%2B$genre&page=$page"))
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        val json = apiGet("https://api.azoramoon.com/api/query?searchTerm=&perPage=30")
        val posts = json?.optJSONArray("posts")
        if (posts != null && posts.length() > 0) buildList {
            for (i in 0 until posts.length()) {
                val post = posts.getJSONObject(i)
                val slug = post.optString("slug", "").ifBlank { continue }
                add(
                    MangaItem(
                        id = "azora_$slug", slug = slug,
                        title = post.optString("postTitle", slug),
                        coverUrl = post.optString("featuredImage", ""),
                        source = source,
                        rating = post.optDouble("averageRating").takeIf { it > 0 }?.toFloat(),
                        url = "${source.baseUrl}/series/$slug"
                    )
                )
            }
        } else parseMangaList(fetchDocument("${source.baseUrl}/series")).take(30)
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        fetchDocument("${source.baseUrl}/series")
            .select("a[href*='/series?genres='],a[href*='genres']")
            .map { it.text().cleanText() }.filter { it.isNotEmpty() }.distinct()
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun parseChaptersFromHtml(
        doc: org.jsoup.nodes.Document,
        slug: String
    ): List<Chapter> {
        val map = linkedMapOf<String, Chapter>()

        // Strategy 1 — containers with "mt-4" / "space-y" classes
        doc.select("div.mt-4,div[class*='space-y']").forEach { container ->
            container.select("a[href*='/chapter']").forEach ch@{ link ->
                val href = link.attr("href")
                val fullHref = if (href.startsWith("http")) href else href.absoluteUrl()
                if (map.containsKey(fullHref)) return@ch
                val numStr = href.substringAfterLast("chapter-").replace("[^0-9.]".toRegex(), "")
                val num = numStr.toFloatOrNull() ?: return@ch
                val row = link.parent() ?: link
                val timeEl = row.selectFirst("time[datetime],time")
                val dateLong = runCatching {
                    timeEl?.attr("datetime")?.let { java.time.Instant.parse(it).toEpochMilli() }
                }.getOrNull()
                map[fullHref] = Chapter(
                    id = "${slug}_$num", mangaId = "azora_$slug", number = num,
                    title = row.select("div.text-xs,[class*='text-gray']").firstOrNull()
                        ?.text()?.cleanText()?.ifBlank { null },
                    url = fullHref, date = dateLong,
                    dateText = timeEl?.text()?.cleanText()
                )
            }
        }

        // Strategy 2 — direct chapter links for this slug
        if (map.size < 5) {
            doc.select("a[href*='/series/$slug/chapter-']").forEach { link ->
                val href = link.attr("href")
                val fullHref = if (href.startsWith("http")) href else href.absoluteUrl()
                if (map.containsKey(fullHref)) return@forEach
                val numStr = href.substringAfterLast("chapter-").replace("[^0-9.]".toRegex(), "")
                val num = numStr.toFloatOrNull() ?: return@forEach
                map[fullHref] = Chapter(
                    id = "${slug}_$num", mangaId = "azora_$slug", number = num, url = fullHref
                )
            }
        }

        return map.values.toList()
    }

    private fun extractTotalChapters(doc: org.jsoup.nodes.Document): Int? =
        Regex("(\\d+)\\s*فصل|فصل\\s*(\\d+)")
            .find(doc.body().text())
            ?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }

    private fun fetchRatingMap(): Map<String, Float> = runCatching {
        val json = apiGet("https://api.azoramoon.com/api/query?searchTerm=&perPage=50")
        val posts = json?.optJSONArray("posts") ?: return@runCatching emptyMap()
        buildMap {
            for (i in 0 until posts.length()) {
                val p = posts.getJSONObject(i)
                val slug = p.optString("slug", "")
                val r = p.optDouble("averageRating").takeIf { it > 0 }?.toFloat()
                if (slug.isNotEmpty() && r != null) put(slug, r)
            }
        }
    }.getOrDefault(emptyMap())

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<MangaItem> {
        val ratingMap by lazy { fetchRatingMap() }
        return doc.select("a[href^='/series/']").mapNotNull { link ->
            val href = link.attr("href")
            if (href.contains("/chapter")) return@mapNotNull null
            val slug = href.removePrefix("/series/").trimEnd('/').substringBefore("/")
            if (slug.isBlank()) return@mapNotNull null
            val img = link.selectFirst("img[src]") ?: return@mapNotNull null
            val title = (link.parent()?.selectFirst("h1,h2")?.text() ?: link.attr("title")).cleanText()
            if (title.isBlank()) return@mapNotNull null
            MangaItem(
                id = "azora_$slug", slug = slug, title = title,
                coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() },
                source = source, rating = ratingMap[slug],
                url = "${source.baseUrl}/series/$slug"
            )
        }.distinctBy { it.id }
    }

    // ─── Kotlin helpers ───────────────────────────────────────────────────────
    private fun String?.ifNullOrBlank(block: () -> String?): String? =
        if (this.isNullOrBlank()) block() else this
}
