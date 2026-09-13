package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import com.exapps.mangaworld.domain.model.MangaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Bookmark(
    val id: String,
    val mangaId: String,
    val chapterUrl: String,
    val pageIndex: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Singleton
class BookmarkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    }

    fun getBookmarks(mangaId: String): List<Bookmark> {
        val json = prefs.getString("$BOOKMARK_PREFIX$mangaId", "[]") ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Bookmark(
                    id = obj.getString("id"),
                    mangaId = obj.getString("mangaId"),
                    chapterUrl = obj.getString("chapterUrl"),
                    pageIndex = obj.getInt("pageIndex"),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addBookmark(mangaId: String, chapterUrl: String, pageIndex: Int, note: String = ""): Bookmark {
        val bookmark = Bookmark(
            id = "bm_${System.currentTimeMillis()}",
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            note = note
        )
        val current = getBookmarks(mangaId).toMutableList()
        current.add(bookmark)
        saveBookmarks(mangaId, current)
        return bookmark
    }

    fun removeBookmark(mangaId: String, bookmarkId: String) {
        val current = getBookmarks(mangaId).toMutableList()
        current.removeAll { it.id == bookmarkId }
        saveBookmarks(mangaId, current)
    }

    fun updateBookmarkNote(mangaId: String, bookmarkId: String, note: String) {
        val current = getBookmarks(mangaId).toMutableList()
        val index = current.indexOfFirst { it.id == bookmarkId }
        if (index >= 0) {
            current[index] = current[index].copy(note = note)
            saveBookmarks(mangaId, current)
        }
    }

    /** BK-1: full snapshot (mangaId → bookmarks) for local backup export. */
    fun snapshot(): Map<String, List<Bookmark>> {
        return prefs.all.mapNotNull { (key, _) ->
            key.removePrefix(BOOKMARK_PREFIX).takeIf { it != key && it.isNotBlank() }?.let { mangaId ->
                mangaId to getBookmarks(mangaId)
            }
        }.toMap()
    }

    /**
     * BK-1: restore a backup snapshot. Id-union per manga (incoming wins ties)
     * so re-imports and cross-device merges never duplicate or lose marks.
     */
    fun restoreBookmarks(incoming: Map<String, List<Bookmark>>) {
        incoming.forEach { (mangaId, items) ->
            if (mangaId.isBlank() || items.isEmpty()) return@forEach
            saveBookmarks(mangaId, mergeBookmarks(getBookmarks(mangaId), items))
        }
    }

    /**
     * Pure id-union, incoming wins on id collision.
     * Internal for unit tests — SharedPreferences is untestable on JVM.
     */
    internal fun mergeBookmarks(
        existing: List<Bookmark>,
        incoming: List<Bookmark>
    ): List<Bookmark> {
        val byId = existing.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        val existingIds = existing.map { it.id }.toSet()
        return existing.mapNotNull { byId[it.id] } +
            incoming.filter { it.id !in existingIds }
    }

    private companion object {
        const val BOOKMARK_PREFIX = "bookmarks_"
    }

    private fun saveBookmarks(mangaId: String, bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        bookmarks.forEach { bm ->
            val obj = JSONObject().apply {
                put("id", bm.id)
                put("mangaId", bm.mangaId)
                put("chapterUrl", bm.chapterUrl)
                put("pageIndex", bm.pageIndex)
                put("note", bm.note)
                put("createdAt", bm.createdAt)
            }
            arr.put(obj)
        }
        prefs.edit().putString("$BOOKMARK_PREFIX$mangaId", arr.toString()).apply()
    }
}
