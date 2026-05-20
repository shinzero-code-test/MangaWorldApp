package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Scraper for azoramoon.com — Astro SSR framework.
 *
 * ════════════════════════════════════════════════════════════
 *  IMPORTANT: azoramoon uses Astro Islands.  ALL data lives
 *  inside <astro-island props="…"> attributes encoded as the
 *  Astro wire format [type, value]:
 *    [0, scalar]  → scalar value
 *    [1, array]   → decoded list
 *
 *  CSS selectors on the rendered DOM return NOTHING useful.
 *  The correct approach is:
 *    1. Fetch raw HTML (OkHttp, not Jsoup)
 *    2. Regex-extract the island whose opts.name matches
 *    3. JSON.parse the props= attribute value
 *    4. Recursively decode the wire format
 * ════════════════════════════════════════════════════════════
 *
 *  Page  → relevant island             → key props
 *  ──────────────────────────────────────────────────────────
 *  Home  → ChapterUpdatesSectionIsland → items (45 latest chapters)
 *  Home  → HomepageMainSliderIsland    → sliderPosts (featured)
 *  Home  → HomepageSharedSliderIsland  → posts (trending)
 *  Detail→ SeriesChaptersPanelIsland   → post + initialChap (ALL chapters)
 *  Chapter→ <img src> in rendered HTML → storage.azoramoon.com/WP-manga/…
 *  Series→ <a href="/series/"> in HTML → rendered manga card list
 */
class AzoraScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.AZORA, settingsRepo) {

    // ─── Wire format decoder ──────────────────────────────────────────────────

    /**
     * Astro encodes props as [type, value]:
     *  type 0 → scalar (String, Int, Boolean, null, Double …)
     *  type 1 → List<Any?> (each element also encoded)
     */
    private fun decodeWire(v: Any?): Any? {
        return when {
            // [type, value] wire pair
            v is JSONArray && v.length() == 2 -> when (v.getInt(0)) {
                0 -> {
                    val inner = v.get(1)
                    when {
                        inner == JSONObject.NULL -> null
                        // type-0 can wrap a nested object — recurse so callers get a Map
                        inner is JSONObject -> decodeWire(inner)
                        // type-0 can also wrap an inner array — recurse
                        inner is JSONArray  -> decodeWire(inner)
                        else -> inner
                    }
                }
                1 -> {
                    val arr = v.getJSONArray(1)
                    (0 until arr.length()).map { decodeWire(arr.get(it)) }
                }
                else -> v
            }
            // Nested object — decode each of its values so callers get a real Map
            v is JSONObject -> v.keys().asSequence()
                .associateWith { k -> decodeWire(v.opt(k)) }
            else -> v
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeList(v: Any?): List<Any?> =
        (decodeWire(v) as? List<Any?>) ?: emptyList()

    private fun decodeStr(v: Any?): String = decodeWire(v)?.toString() ?: ""
    private fun decodeBool(v: Any?): Boolean = decodeWire(v) as? Boolean ?: false
    private fun decodeInt(v: Any?): Int =
        (decodeWire(v) as? Number)?.toInt() ?: 0
    private fun decodeFloat(v: Any?): Float =
        (decodeWire(v) as? Number)?.toFloat() ?: 0f
    private fun decodeLong(v: Any?): Long? =
        (decodeWire(v) as? Number)?.toLong()

    // ─── Raw HTTP (Astro props must come from raw HTML, not Jsoup) ────────────

    private suspend fun fetchRawHtml(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = getCookiesForDomain(url)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ar,en;q=0.9")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            body
        }.getOrDefault("")
    }

    /**
     * Fast string-based parser: finds every <astro-island> tag, extracts its
     * opts and props attribute values, checks if opts.name matches [componentName],
     * then JSON-parses and returns props.
     *
     * Uses indexOf/substring instead of regex to avoid catastrophic backtracking
     * on the 1 MB+ detail pages that embed 300+ chapters in props.
     */
    private fun extractIslandProps(rawHtml: String, componentName: String): JSONObject? {
        var pos = 0
        while (true) {
            val tagStart = rawHtml.indexOf("<astro-island", pos)
            if (tagStart < 0) break

            // Walk to end of opening tag, respecting quoted attribute values
            val tagEnd = findOpenTagEnd(rawHtml, tagStart)
            if (tagEnd < 0) break
            pos = tagEnd + 1

            val tagContent = rawHtml.substring(tagStart, tagEnd + 1)
            val optsVal = extractAttrValue(tagContent, "opts") ?: continue
            if (componentName !in optsVal.htmlUnesc()) continue
            val propsVal = extractAttrValue(tagContent, "props") ?: continue
            return runCatching { JSONObject(propsVal.htmlUnesc()) }.getOrNull()
        }
        return null
    }

    /** Returns the index of the closing '>' of the opening tag starting at [start]. */
    private fun findOpenTagEnd(html: String, start: Int): Int {
        var i = start
        var inQuote = false
        var quoteChar = ' '
        while (i < html.length) {
            val c = html[i]
            when {
                inQuote       -> { if (c == quoteChar) inQuote = false }
                c == '"'    -> { inQuote = true; quoteChar = c }
                c == '\''   -> { inQuote = true; quoteChar = c }
                c == '>'    -> return i
            }
            i++
        }
        return -1
    }

    /** Extracts the value of attribute [name] from a raw HTML tag string. */
    private fun extractAttrValue(tagContent: String, name: String): String? {
        val marker = "$name=\""
        val start = tagContent.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = tagContent.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return tagContent.substring(valueStart, valueEnd)
    }

    private fun String.htmlUnesc(): String =
        this.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")

    // ─── Search API (JSON REST — works correctly as-is) ───────────────────────

    private suspend fun apiGet(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = getCookiesForDomain(url)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "${source.baseUrl}/")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (body.isBlank() || !body.trimStart().startsWith("{")) null
            else JSONObject(body)
        }.getOrNull()
    }

    // ─── Home ─────────────────────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val rawHtml = fetchRawHtml(source.baseUrl)

        // ── 1. Latest chapters ← ChapterUpdatesSectionIsland.items ────────────
        //    item keys: chapterId, chapterSlug, chapterNumber, chapterTitle,
        //               isPaid, createdAt, seriesSlug, seriesTitle, seriesImage,
        //               uploaderName
        val latestChapters = mutableListOf<LatestChapterItem>()
        val chapterIslandProps = extractIslandProps(rawHtml, "ChapterUpdatesSectionIsland")
        if (chapterIslandProps != null) {
            val items = decodeList(chapterIslandProps.opt("items"))
            for (item in items) {
                val obj = item as? Map<*, *> ?: continue
                val seriesSlug = decodeStr(obj["seriesSlug"])
                if (seriesSlug.isBlank()) continue
                val chapterNum  = decodeFloat(obj["chapterNumber"])
                val chapterSlug = decodeStr(obj["chapterSlug"]).ifBlank { "chapter-${chapterNum.toInt()}" }
                val createdAt   = decodeStr(obj["createdAt"])
                val dateLong = runCatching {
                    java.time.Instant.parse(createdAt).toEpochMilli()
                }.getOrNull()
                val isNew = dateLong != null &&
                        System.currentTimeMillis() - dateLong < 24 * 3600 * 1000L

                latestChapters.add(
                    LatestChapterItem(
                        mangaId      = "azora_$seriesSlug",
                        mangaSlug    = seriesSlug,
                        mangaTitle   = decodeStr(obj["seriesTitle"]),
                        coverUrl     = decodeStr(obj["seriesImage"]),
                        chapterNumber= chapterNum,
                        chapterTitle = decodeStr(obj["chapterTitle"]).ifBlank { null },
                        chapterUrl   = "${source.baseUrl}/series/$seriesSlug/$chapterSlug",
                        timeAgo      = createdAt.take(10),
                        publishedAt  = dateLong,
                        source       = source,
                        isNew        = isNew
                    )
                )
            }
        }

        // ── 2. Featured slider ← HomepageMainSliderIsland.sliderPosts ──────────
        //    post keys: id, slug, postTitle, featuredImage, seriesStatus,
        //               seriesType, averageRating, genres, _count, postContent, …
        val featured = mutableListOf<MangaItem>()
        val sliderProps = extractIslandProps(rawHtml, "HomepageMainSliderIsland")
        if (sliderProps != null) {
            for (p in decodeList(sliderProps.opt("sliderPosts"))) {
                val obj = p as? Map<*, *> ?: continue
                val slug = decodeStr(obj["slug"])
                if (slug.isBlank()) continue
                featured.add(buildMangaItem(obj, slug))
            }
        }

        // ── 3. Trending ← HomepageSharedSliderIsland.posts ────────────────────
        //    post keys: id, slug, postTitle, featuredImage, seriesType,
        //               genres, lastChapter, sumViews, averageRating
        val trending = mutableListOf<MangaItem>()
        val sharedProps = extractIslandProps(rawHtml, "HomepageSharedSliderIsland")
        if (sharedProps != null) {
            for (p in decodeList(sharedProps.opt("posts"))) {
                val obj = p as? Map<*, *> ?: continue
                val slug = decodeStr(obj["slug"])
                if (slug.isBlank()) continue
                trending.add(buildMangaItem(obj, slug))
            }
        }

        HomeData(
            featured       = featured,
            latestChapters = latestChapters,
            trending       = trending.ifEmpty { featured }
        )
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val url     = "${source.baseUrl}/series/$slug"
        val rawHtml = fetchRawHtml(url)

        // ── SeriesChaptersPanelIsland ──────────────────────────────────────────
        //   props.post      → full post object (all manga metadata)
        //   props.initialChap → ALL chapters pre-embedded in the HTML (no API call!)
        //   props.totalChapterCount → Int
        val props = extractIslandProps(rawHtml, "SeriesChaptersPanelIsland")
            ?: error("SeriesChaptersPanelIsland not found in $url")

        // ── Post / manga metadata ──────────────────────────────────────────────
        val postRaw     = props.opt("post")
        val postDecoded = decodeWire(postRaw)
        val post        = postDecoded as? Map<*, *> ?: error("post is null in $url")

        val title        = decodeStr(post["postTitle"]).cleanText()
        val coverUrl     = decodeStr(post["featuredImage"])
        val seriesStatus = decodeStr(post["seriesStatus"])
        val seriesType   = decodeStr(post["seriesType"])
        val avgRating    = decodeFloat(post["averageRating"]).takeIf { it > 0f }
        val totalViews   = decodeStr(post["totalViews"]).ifBlank { null }

        // Genres: list of {id:[0,N], name:[0,"..."], color:[0,"..."]}
        val genres: List<String> = run {
            val gl = decodeList(post["genres"])
            gl.mapNotNull { g ->
                val gm = g as? Map<*, *> ?: return@mapNotNull null
                decodeStr(gm["name"]).cleanText().ifBlank { null }
            }
        }
        val tags = genres
        val altTitles = decodeStr(post["alternativeTitles"]).split("/", "|", "،", ",")
            .map { it.cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
        val artistName = decodeStr(post["artist"]).cleanText().ifBlank { null }

        // Description: postContent is HTML — strip tags
        val description = decodeStr(post["postContent"])
            .let { html -> Regex("<[^>]+>").replace(html, "") }
            .replace("&nbsp;", " ")
            .trim()
            .cleanText()

        // _count.chapters gives the authoritative count
        val countMap    = decodeWire(post["_count"]) as? Map<*, *>
        val chapterCount= decodeInt(countMap?.get("chapters"))

        // ── Chapters — ALL embedded in initialChap prop ───────────────────────
        //   chapter keys: id, number, slug, title, createdAt, isLocked,
        //                 isPermanentlyLocked, chapterStatus, price, isAccessible
        val totalFromProp = decodeInt(props.opt("totalChapterCount"))
        val chaptersRaw   = decodeList(props.opt("initialChap"))
        val chapters = chaptersRaw.mapNotNull { c ->
            val obj  = c as? Map<*, *> ?: return@mapNotNull null
            val num  = decodeFloat(obj["number"]).takeIf { it > 0 } ?: return@mapNotNull null
            val cslug= decodeStr(obj["slug"]).ifBlank { "chapter-${num.toInt()}" }
            val chUrl= "${source.baseUrl}/series/$slug/$cslug"
            val chapterCover = (decodeWire(obj["mangaPost"]) as? Map<*, *>)?.let { mp ->
                decodeStr(mp["featuredImage"]).ifBlank { null }
            }.orEmpty()
            val cdate= decodeStr(obj["createdAt"])
            val dateLong = runCatching {
                java.time.Instant.parse(cdate).toEpochMilli()
            }.getOrNull()
            val locked = decodeBool(obj["isLocked"])
                      || decodeBool(obj["isPermanentlyLocked"])
                      || (decodeFloat(obj["price"]) > 0)
            Chapter(
                id        = "${slug}_$num",
                mangaId   = "azora_$slug",
                number    = num,
                title     = decodeStr(obj["title"]).cleanText().ifBlank { null },
                url       = chUrl,
                coverUrl  = chapterCover,
                date      = dateLong,
                dateText  = cdate.take(10).ifBlank { null },
                isPaid    = locked
            )
        }

        val related = run {
            val recProps = extractIslandProps(rawHtml, "SeriesRecommendationsIsland")
            val recPosts = decodeList(recProps?.opt("posts"))
            recPosts.mapNotNull { p ->
                val obj = p as? Map<*, *> ?: return@mapNotNull null
                val rslug = decodeStr(obj["slug"]).ifBlank { return@mapNotNull null }
                MangaItem(
                    id = "azora_$rslug",
                    slug = rslug,
                    title = decodeStr(obj["postTitle"]).cleanText().ifBlank { rslug },
                    coverUrl = decodeStr(obj["featuredImage"]),
                    source = source,
                    genres = decodeList(obj["genres"]).mapNotNull { g -> (g as? Map<*, *>)?.let { gm -> decodeStr(gm["name"]).cleanText().ifBlank { null } } },
                    status = MangaStatus.from(decodeStr(obj["seriesStatus"])),
                    type = MangaType.from(decodeStr(obj["seriesType"])),
                    url = "${source.baseUrl}/series/$rslug"
                )
            }.distinctBy { it.id }
        }

        MangaDetail(
            id           = "azora_$slug",
            slug         = slug,
            title        = title.ifBlank { slug },
            coverUrl     = coverUrl,
            source       = source,
            alternativeTitles = altTitles,
            artistName   = artistName,
            description  = description,
            genres       = genres,
            tags         = tags,
            status       = MangaStatus.from(seriesStatus),
            type         = MangaType.from(seriesType),
            rating       = avgRating,
            views        = totalViews,
            totalChapters= totalFromProp.takeIf { it > 0 } ?: chapterCount,
            chapters     = chapters.sortedByDescending { it.number },
            relatedManga = related,
            url          = url
        )
    }

    // ─── Chapter pages ────────────────────────────────────────────────────────
    //
    // Images are SSR-rendered directly into the HTML — no astro-island needed.
    // URL pattern: storage.azoramoon.com/WP-manga/data/manga_{hash}/{ch_hash}/N.jpg

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl)

        // Primary: images inside .comic-images-wrapper (SSR-rendered)
        var pages = doc.select("img[src*='WP-manga'], img[src*='storage.azoramoon.com']")
            .filter { it.attr("src").contains("WP-manga") || it.attr("src").matches(
                Regex(".*storage\\.azoramoon\\.com/\\d{4}/.*")
            )}
            .mapIndexed { idx, img ->
                val readerIdx = img.attr("data-reader-index").toIntOrNull() ?: idx
                val src = img.attr("abs:src").encodeForUrl()
                ChapterPage(index = readerIdx, url = src,
                    headers = buildImageHeaders(src, chapterUrl))
            }
            .sortedBy { it.index }

        // Fallback: any img with storage.azoramoon.com
        if (pages.isEmpty()) {
            pages = doc.select("img[src*='storage.azoramoon.com']")
                .filter { !it.attr("src").contains("logo") && !it.attr("src").contains("avatar") }
                .mapIndexed { idx, img ->
                    val src = img.attr("abs:src").encodeForUrl()
                    ChapterPage(idx, src, headers = buildImageHeaders(src, chapterUrl))
                }
        }

        pages
    }

    // ─── Search (REST API — correct and working) ──────────────────────────────

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val enc   = java.net.URLEncoder.encode(query, "UTF-8")
        val json  = apiGet("https://api.azoramoon.com/api/query?searchTerm=$enc&perPage=30")
            ?: return@runCatching emptyList()
        val posts = json.optJSONArray("posts") ?: return@runCatching emptyList()
        val result = mutableListOf<MangaItem>()
        for (i in 0 until posts.length()) {
            val post = posts.getJSONObject(i)
            val slug = post.optString("slug", "")
            if (slug.isBlank()) continue
            result.add(
                MangaItem(
                    id       = "azora_$slug",
                    slug     = slug,
                    title    = post.optString("postTitle", slug),
                    coverUrl = post.optString("featuredImage", ""),
                    source   = source,
                    status   = MangaStatus.from(post.optString("seriesStatus")),
                    type     = MangaType.from(post.optString("seriesType")),
                    rating   = post.optDouble("averageRating").takeIf { it > 0 }?.toFloat(),
                    url      = "${source.baseUrl}/series/$slug"
                )
            )
        }
        result
    }

    // ─── Genre listing ────────────────────────────────────────────────────────
    // Series page is STATIC HTML (only StaticPageShell island) — real selectors work.
    //
    // Manga card structure (confirmed from azoraseries.html):
    //   <a href="/series/{slug}" title="{title}">
    //     <img src="{cover}">
    //     <span class="px-2 py-1 … text-white …">{type}</span>   ← type badge
    //   </a>
    //   <a href="/series/{slug}" class="… font-bold …">{title}</a>
    //   <p class="font-normal text-xs">مستمر</p>                  ← status

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val enc = java.net.URLEncoder.encode(genre, "UTF-8")
        parseMangaListPage(fetchDocument("${source.baseUrl}/series?genres=%2B$enc&page=$page"))
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        // API gives rating-sorted results
        val json  = apiGet("https://api.azoramoon.com/api/query?searchTerm=&perPage=30")
        val posts = json?.optJSONArray("posts")
        if (posts != null && posts.length() > 0) {
            val result = mutableListOf<MangaItem>()
            for (i in 0 until posts.length()) {
                val post = posts.getJSONObject(i)
                val slug = post.optString("slug", "")
                if (slug.isBlank()) continue
                result.add(
                    MangaItem(
                        id       = "azora_$slug",
                        slug     = slug,
                        title    = post.optString("postTitle", slug),
                        coverUrl = post.optString("featuredImage", ""),
                        source   = source,
                        rating   = post.optDouble("averageRating").takeIf { it > 0 }?.toFloat(),
                        status   = MangaStatus.from(post.optString("seriesStatus", "")),
                        type     = MangaType.from(post.optString("seriesType", "")),
                        url      = "${source.baseUrl}/series/$slug"
                    )
                )
            }
            result
        } else {
            parseMangaListPage(fetchDocument("${source.baseUrl}/series"))
        }
    }

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val baseUrl = buildString {
            append("${source.baseUrl}/series?page=$page")
            genre?.takeIf { it.isNotBlank() }?.let {
                append("&genres=%2B")
                append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
        }
        val items = parseMangaListPage(fetchDocument(baseUrl))
            .filter { status == null || it.status == status || it.status == MangaStatus.UNKNOWN }
            .filter { type == null || it.type == type || it.type == MangaType.UNKNOWN }
        when (sortBy) {
            SortBy.RATING -> items.sortedByDescending { it.rating ?: 0f }
            SortBy.POPULARITY -> items.sortedByDescending { it.latestChapter ?: 0 }
            SortBy.OLDEST -> items.sortedBy { it.title.lowercase() }
            SortBy.LATEST -> items
        }
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        // Genres are rendered as filter links on the series page
        val doc = fetchDocument("${source.baseUrl}/series")
        doc.select("a[href*='/series?genres='], a[href*='genres=']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a MangaItem from a decoded island prop map.
     * Works for both sliderPosts (full) and posts (simplified).
     */
    private fun buildMangaItem(obj: Map<*, *>, slug: String): MangaItem {
        val genres = decodeList(obj["genres"]).mapNotNull { g ->
            when (g) {
                is Map<*, *> -> decodeStr(g["name"]).cleanText().ifBlank { null }
                is String    -> g.ifBlank { null }
                else         -> null
            }
        }
        return MangaItem(
            id       = "azora_$slug",
            slug     = slug,
            title    = decodeStr(obj["postTitle"]).cleanText().ifBlank { slug },
            coverUrl = decodeStr(obj["featuredImage"]),
            source   = source,
            status   = MangaStatus.from(decodeStr(obj["seriesStatus"])),
            type     = MangaType.from(decodeStr(obj["seriesType"])),
            rating   = decodeFloat(obj["averageRating"]).takeIf { it > 0f },
            genres   = genres,
            url      = "${source.baseUrl}/series/$slug"
        )
    }

    /**
     * Parse the series listing page (static HTML — no astro-islands needed).
     *
     * Confirmed structure from azoraseries.html:
     *   <a href="/series/{slug}" title="{title}">
     *     <img src="{cover}" …/>
     *     <span class="… text-white …">{type}</span>
     *   </a>
     *   <a href="/series/{slug}" class="… font-bold …">{title}</a>
     *   <p class="font-normal text-xs">{statusAr}</p>
     */
    private fun parseMangaListPage(doc: org.jsoup.nodes.Document): List<MangaItem> {
        val items = mutableListOf<MangaItem>()
        val seen  = mutableSetOf<String>()

        // Each card root has a link with a title attribute — that's the anchor
        doc.select("a[href^='/series/'][title]").forEach { link ->
            val href  = link.attr("href")
            val slug  = href.removePrefix("/series/").trimEnd('/').substringBefore("/")
            if (slug.isEmpty() || slug.contains("/") || !seen.add(slug)) return@forEach

            val img   = link.selectFirst("img[src]") ?: return@forEach
            val title = link.attr("title").cleanText().ifBlank {
                link.nextElementSibling()?.text()?.cleanText() ?: slug
            }
            val typeSpan = link.selectFirst("span.text-white")
            val coverUrl = img.attr("abs:src").ifEmpty { img.attr("src").absoluteUrl() }

            // Status and type live in sibling elements after the link
            val parent   = link.parent() ?: link
            val statusEl = parent.select("p.font-normal").firstOrNull()
            val statusTxt= statusEl?.text() ?: ""

            items.add(
                MangaItem(
                    id       = "azora_$slug",
                    slug     = slug,
                    title    = title,
                    coverUrl = coverUrl,
                    source   = source,
                    type     = MangaType.from(typeSpan?.text()),
                    status   = MangaStatus.from(statusTxt),
                    url      = "${source.baseUrl}/series/$slug"
                )
            )
        }
        return items
    }

    private fun String?.ifNullOrBlank(block: () -> String?): String? =
        if (this.isNullOrBlank()) block() else this
}
