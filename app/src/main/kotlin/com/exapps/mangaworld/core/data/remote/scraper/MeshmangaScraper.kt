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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class MeshmangaScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : BaseScraperImpl(client, MangaSource.MESHMANGA, settingsRepo) {

    private val apiBase = "https://appswat.com/v2/api/v2"
    @Volatile private var genreIdCache: Map<String, Int>? = null

    override suspend fun getHomeData(): Result<HomeData> = runCatching {
        val featured = fetchSeriesPage("$apiBase/series/?is_hot=true&page_size=20")
        val latestJson = apiGetObject("$apiBase/chapters/?page_size=20&order_by=-created_at")
        val latest = latestJson?.optJSONArray("results")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                latestChapterItemFrom(array.optJSONObject(i))
            }
        }.orEmpty()

        HomeData(
            featured = featured.take(8),
            latestChapters = latest,
            trending = featured
        )
    }

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = runCatching {
        val seriesId = slug.substringBefore('-').substringBefore('_').ifBlank { slug }
        val series = apiGetObject("$apiBase/series/$seriesId/")
            ?: error("Series $seriesId not found")
        val chapters = fetchAllChapters(seriesId)

        MangaDetail(
            id = "meshmanga_$seriesId",
            slug = seriesId,
            title = series.optString("title").cleanText().ifBlank { seriesId },
            coverUrl = extractPosterUrl(series),
            source = source,
            description = series.optString("story").cleanText(),
            genres = extractGenres(series),
            status = MangaStatus.from(series.optJSONObject("status")?.optString("name")),
            type = MangaType.from(series.optJSONObject("type")?.optString("name")),
            rating = series.optString("rating").toFloatOrNull(),
            totalChapters = series.optInt("chapters_count").takeIf { it > 0 } ?: chapters.size,
            views = series.opt("views_count")?.toString(),
            lastUpdated = series.optString("updated_at_humanized").ifBlank { null },
            chapters = chapters,
            url = "${source.baseUrl}/series/$seriesId"
        )
    }

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val chapterId = Regex("cid-(\\d+)").find(chapterUrl)?.groupValues?.get(1)
            ?: error("Missing Meshmanga chapter id in $chapterUrl")
        val json = apiGetObject("$apiBase/chapters/$chapterId/")
            ?: error("Chapter $chapterId not found")
        val images = json.optJSONArray("images") ?: JSONArray()

        (0 until images.length())
            .mapNotNull { i -> images.optJSONObject(i) }
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .mapIndexedNotNull { index, image ->
                val src = image.optString("image").encodeForUrl().takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                ChapterPage(
                    index = image.optInt("order").takeIf { it > 0 }?.minus(1) ?: index,
                    url = src,
                    headers = buildImageHeaders(src, source.baseUrl + "/")
                )
            }
            .sortedBy { it.index }
    }

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> = runCatching {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        fetchSeriesPage("$apiBase/series/?search=$encoded&page=$page&page_size=30")
    }

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> = runCatching {
        val genreId = resolveGenreId(genre) ?: return@runCatching emptyList()
        fetchSeriesPage("$apiBase/series/?genre=$genreId&page=$page&page_size=30")
    }

    override suspend fun getPopularManga(): Result<List<MangaItem>> = runCatching {
        fetchSeriesPage("$apiBase/series/?is_hot=true&page_size=30")
    }

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = runCatching {
        val params = mutableListOf("page=$page", "page_size=30")
        genre?.takeIf { it.isNotBlank() }?.let { resolveGenreId(it)?.let { id -> params += "genre=$id" } }
        when (sortBy) {
            SortBy.POPULARITY -> params += "ordering=-views_count"
            SortBy.RATING -> params += "ordering=-rating"
            SortBy.OLDEST -> params += "ordering=title"
            SortBy.LATEST -> params += "ordering=-updated_at"
        }
        fetchSeriesPage("$apiBase/series/?${params.joinToString("&")}")
            .filter { status == null || it.status == status || it.status == MangaStatus.UNKNOWN }
            .filter { type == null || it.type == type || it.type == MangaType.UNKNOWN }
    }

    override suspend fun getGenres(): Result<List<String>> = runCatching {
        val genres = apiGetArray("$apiBase/genres/?page_size=200").orEmpty()
        val mapped = genres
            .mapNotNull { obj ->
                val name = obj.optString("name").cleanText().ifBlank { null } ?: return@mapNotNull null
                name to obj.optInt("id")
            }
            .toMap()
        genreIdCache = mapped
        mapped.keys.sorted()
    }

    private suspend fun fetchSeriesPage(url: String): List<MangaItem> {
        val json = apiGetObject(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length())
            .mapNotNull { i -> results.optJSONObject(i) }
            .mapNotNull { seriesItemFrom(it) }
    }

    private suspend fun fetchAllChapters(seriesId: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var nextUrl: String? = "$apiBase/chapters/?serie=$seriesId&page_size=100&order_by=-order"

        while (!nextUrl.isNullOrBlank()) {
            val json = apiGetObject(nextUrl) ?: break
            val results = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                chapterFrom(seriesId, results.optJSONObject(i))?.let(chapters::add)
            }
            nextUrl = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }

        return chapters
            .distinctBy { it.url }
            .sortedByDescending { it.number }
    }

    private suspend fun resolveGenreId(genre: String): Int? {
        val cached = genreIdCache
        if (cached != null) return cached[genre]
        getGenres().getOrDefault(emptyList())
        return genreIdCache?.get(genre)
    }

    private fun seriesItemFrom(obj: JSONObject?): MangaItem? {
        if (obj == null) return null
        val seriesId = obj.optLong("id").takeIf { it > 0 }?.toString() ?: return null
        val title = obj.optString("title").cleanText().ifBlank { return null }
        return MangaItem(
            id = "meshmanga_$seriesId",
            slug = seriesId,
            title = title,
            coverUrl = extractPosterUrl(obj),
            source = source,
            genres = extractGenres(obj),
            status = MangaStatus.from(obj.optJSONObject("status")?.optString("name")),
            type = MangaType.from(obj.optJSONObject("type")?.optString("name")),
            rating = obj.optString("rating").toFloatOrNull(),
            totalChapters = obj.optInt("chapters_count").takeIf { it > 0 },
            url = "${source.baseUrl}/series/$seriesId"
        )
    }

    private fun latestChapterItemFrom(obj: JSONObject?): LatestChapterItem? {
        if (obj == null) return null
        val series = obj.optJSONObject("serie") ?: return null
        val seriesId = series.optLong("id").takeIf { it > 0 }?.toString() ?: return null
        val chapterId = obj.optLong("id").takeIf { it > 0 } ?: return null
        val chapterNumber = parseChapterNumber(obj.optString("chapter").ifBlank { obj.optString("title") })
            ?: return null
        val publishedAt = obj.optString("created_at").takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        return LatestChapterItem(
            mangaId = "meshmanga_$seriesId",
            mangaSlug = seriesId,
            mangaTitle = series.optString("title").cleanText().ifBlank { seriesId },
            coverUrl = extractPosterUrl(series),
            chapterNumber = chapterNumber,
            chapterTitle = obj.optString("title").cleanText().ifBlank { null },
            chapterUrl = buildChapterUrl(seriesId, chapterId, chapterNumber),
            timeAgo = obj.optString("created_at_humanized"),
            publishedAt = publishedAt,
            source = source,
            isNew = obj.optString("created_at_humanized").contains("ساعة")
        )
    }

    private fun chapterFrom(seriesId: String, obj: JSONObject?): Chapter? {
        if (obj == null) return null
        val chapterId = obj.optLong("id").takeIf { it > 0 } ?: return null
        val chapterNumber = parseChapterNumber(obj.optString("chapter").ifBlank { obj.optString("title") })
            ?: return null
        val createdAt = obj.optString("created_at").ifBlank { null }
        val date = createdAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return Chapter(
            id = "${seriesId}_$chapterId",
            mangaId = "meshmanga_$seriesId",
            number = chapterNumber,
            title = obj.optString("title").cleanText().ifBlank { null },
            url = buildChapterUrl(seriesId, chapterId, chapterNumber),
            date = date,
            dateText = obj.optString("created_at_humanized").ifBlank { null },
            views = obj.optInt("views_count").takeIf { it > 0 }
        )
    }

    private fun buildChapterUrl(seriesId: String, chapterId: Long, chapterNumber: Float): String {
        val displayNumber = if (chapterNumber == chapterNumber.toInt().toFloat()) {
            chapterNumber.toInt().toString()
        } else {
            chapterNumber.toString()
        }
        return "${source.baseUrl}/read/$seriesId/cid-$chapterId/chapter-$displayNumber"
    }

    private fun parseChapterNumber(text: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()

    private fun extractPosterUrl(obj: JSONObject): String {
        val poster = obj.optJSONObject("poster")
        return (poster?.optString("medium").takeUnless { it.isNullOrBlank() }
            ?: poster?.optString("thumbnail").orEmpty()).encodeForUrl()
    }

    private fun extractGenres(obj: JSONObject): List<String> {
        val genres = obj.optJSONArray("genres") ?: return emptyList()
        return (0 until genres.length())
            .mapNotNull { i -> genres.optJSONObject(i)?.optString("name")?.cleanText()?.ifBlank { null } }
    }

    private suspend fun apiGetObject(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = getCookiesForDomain(url)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", source.baseUrl + "/")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (body.isBlank()) null else JSONObject(body)
        }.getOrNull()
    }

    private suspend fun apiGetArray(url: String): List<JSONObject>? = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = getCookiesForDomain(url)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", source.baseUrl + "/")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (body.isBlank()) return@runCatching null
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) }
        }.getOrNull()
    }

}
