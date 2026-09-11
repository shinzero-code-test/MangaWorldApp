package com.exapps.mangaworld.core.data.local

import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json codec for the home-screen offline cache (item 9).
 *
 * Pure functions (no Android, no Room) so the round-trip is JVM-testable.
 * Unknown source ids decode via [MangaSource.fromIdOrNull] and are dropped —
 * a corrupt row must not plant phantom sources (backup-convention).
 */
object HomeCacheCodec {
    const val MAX_ITEMS_PER_SECTION = 60

    fun encode(data: HomeData): String =
        JSONObject()
            .put("featured", JSONArray(data.featured.take(MAX_ITEMS_PER_SECTION).map { it.toJson() }))
            .put("latest", JSONArray(data.latestChapters.take(MAX_ITEMS_PER_SECTION).map { it.toJson() }))
            .put("trending", JSONArray(data.trending.take(MAX_ITEMS_PER_SECTION).map { it.toJson() }))
            .toString()

    fun decode(raw: String): HomeData? = runCatching {
        val root = JSONObject(raw)
        HomeData(
            featured = root.optJSONArray("featured")?.toMangaItems().orEmpty(),
            latestChapters = root.optJSONArray("latest")?.toLatestItems().orEmpty(),
            trending = root.optJSONArray("trending")?.toMangaItems().orEmpty()
        )
    }.getOrNull()

    private fun MangaItem.toJson(): JSONObject = JSONObject()
        .put("id", id.take(256))
        .put("slug", slug.take(256))
        .put("title", title.take(300))
        .put("coverUrl", coverUrl.take(2048))
        .put("sourceId", source.id)
        .put("genres", JSONArray(genres.take(20)))
        .put("status", status.name)
        .put("type", type.name)
        .put("rating", rating?.toDouble())
        .put("latestChapter", latestChapter)
        .put("totalChapters", totalChapters)
        .put("lastUpdated", lastUpdated)
        .put("isNew", isNew)
        .put("url", url.take(2048))

    private fun LatestChapterItem.toJson(): JSONObject = JSONObject()
        .put("mangaId", mangaId.take(256))
        .put("mangaSlug", mangaSlug.take(256))
        .put("mangaTitle", mangaTitle.take(300))
        .put("coverUrl", coverUrl.take(2048))
        .put("chapterNumber", chapterNumber.toDouble())
        .put("chapterTitle", chapterTitle?.take(300))
        .put("chapterUrl", chapterUrl.take(2048))
        .put("timeAgo", timeAgo.take(64))
        .put("publishedAt", publishedAt)
        .put("sourceId", source.id)
        .put("isNew", isNew)

    private fun JSONArray.toMangaItems(): List<MangaItem> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val source = MangaSource.fromIdOrNull(o.optString("sourceId")) ?: return@mapNotNull null
            MangaItem(
                id = o.optString("id"),
                slug = o.optString("slug"),
                title = o.optString("title"),
                coverUrl = o.optString("coverUrl"),
                source = source,
                genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList(),
                status = o.optString("status").let { runCatching { MangaStatus.valueOf(it) }.getOrDefault(MangaStatus.UNKNOWN) },
                type = o.optString("type").let { runCatching { MangaType.valueOf(it) }.getOrDefault(MangaType.UNKNOWN) },
                rating = if (o.isNull("rating")) null else o.optDouble("rating").toFloat(),
                latestChapter = if (o.isNull("latestChapter")) null else o.optInt("latestChapter"),
                totalChapters = if (o.isNull("totalChapters")) null else o.optInt("totalChapters"),
                lastUpdated = if (o.isNull("lastUpdated")) null else o.optLong("lastUpdated"),
                isNew = o.optBoolean("isNew"),
                url = o.optString("url")
            )
        }

    private fun JSONArray.toLatestItems(): List<LatestChapterItem> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val source = MangaSource.fromIdOrNull(o.optString("sourceId")) ?: return@mapNotNull null
            LatestChapterItem(
                mangaId = o.optString("mangaId"),
                mangaSlug = o.optString("mangaSlug"),
                mangaTitle = o.optString("mangaTitle"),
                coverUrl = o.optString("coverUrl"),
                chapterNumber = o.optDouble("chapterNumber").toFloat(),
                chapterTitle = o.optString("chapterTitle").takeIf { it.isNotEmpty() },
                chapterUrl = o.optString("chapterUrl"),
                timeAgo = o.optString("timeAgo"),
                publishedAt = if (o.isNull("publishedAt")) null else o.optLong("publishedAt"),
                source = source,
                isNew = o.optBoolean("isNew")
            )
        }
}
