package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryEntry(
    val mangaId: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val lastChapterNumber: Float,
    val lastChapterUrl: String,
    val lastReadAt: Long,
    val readChapters: Int = 0,
    val totalChapters: Int = 0
)

@Singleton
class HistoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("reading_history", Context.MODE_PRIVATE)
    }

    fun getHistory(limit: Int = 50): List<HistoryEntry> {
        val json = prefs.getString("history", "[]") ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                HistoryEntry(
                    mangaId = obj.getString("mangaId"),
                    title = obj.getString("title"),
                    coverUrl = obj.getString("coverUrl"),
                    sourceId = obj.getString("sourceId"),
                    lastChapterNumber = obj.getDouble("lastChapterNumber").toFloat(),
                    lastChapterUrl = obj.getString("lastChapterUrl"),
                    lastReadAt = obj.getLong("lastReadAt"),
                    readChapters = obj.optInt("readChapters", 0),
                    totalChapters = obj.optInt("totalChapters", 0)
                )
            }.sortedByDescending { it.lastReadAt }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToHistory(entry: HistoryEntry) {
        val current = getHistory(200).toMutableList()
        current.removeAll { it.mangaId == entry.mangaId }
        current.add(0, entry)
        saveHistory(current.take(200))
    }

    fun removeFromHistory(mangaId: String) {
        val current = getHistory(200).toMutableList()
        current.removeAll { it.mangaId == mangaId }
        saveHistory(current)
    }

    fun clearHistory() {
        prefs.edit().remove("history").apply()
    }

    private fun saveHistory(history: List<HistoryEntry>) {
        val arr = JSONArray()
        history.forEach { entry ->
            val obj = JSONObject().apply {
                put("mangaId", entry.mangaId)
                put("title", entry.title)
                put("coverUrl", entry.coverUrl)
                put("sourceId", entry.sourceId)
                put("lastChapterNumber", entry.lastChapterNumber.toDouble())
                put("lastChapterUrl", entry.lastChapterUrl)
                put("lastReadAt", entry.lastReadAt)
                put("readChapters", entry.readChapters)
                put("totalChapters", entry.totalChapters)
            }
            arr.put(obj)
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
