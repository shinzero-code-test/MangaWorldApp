package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import com.exapps.mangaworld.domain.model.MangaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MangaSuggestion(
    val mangaId: String,
    val title: String,
    val sourceId: String,
    val relevance: Float,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Singleton
class SuggestionsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("manga_suggestions", Context.MODE_PRIVATE)
    }

    fun getSuggestions(limit: Int = 20): List<MangaSuggestion> {
        val json = prefs.getString("suggestions", "[]") ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                MangaSuggestion(
                    mangaId = obj.getString("mangaId"),
                    title = obj.getString("title"),
                    sourceId = obj.getString("sourceId"),
                    relevance = obj.getDouble("relevance").toFloat(),
                    lastUpdated = obj.optLong("lastUpdated", 0L)
                )
            }.sortedByDescending { it.relevance }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateSuggestions(suggestions: List<MangaSuggestion>) {
        val arr = JSONArray()
        suggestions.forEach { s ->
            val obj = JSONObject().apply {
                put("mangaId", s.mangaId)
                put("title", s.title)
                put("sourceId", s.sourceId)
                put("relevance", s.relevance.toDouble())
                put("lastUpdated", s.lastUpdated)
            }
            arr.put(obj)
        }
        prefs.edit().putString("suggestions", arr.toString()).apply()
    }

    fun addSuggestion(manga: MangaItem, relevance: Float = 0.5f) {
        val current = getSuggestions(100).toMutableList()
        current.removeAll { it.mangaId == manga.id }
        current.add(
            MangaSuggestion(
                mangaId = manga.id,
                title = manga.title,
                sourceId = manga.source.id,
                relevance = relevance
            )
        )
        updateSuggestions(current.take(100))
    }

    fun removeSuggestion(mangaId: String) {
        val current = getSuggestions(100).toMutableList()
        current.removeAll { it.mangaId == mangaId }
        updateSuggestions(current)
    }

    fun clear() {
        prefs.edit().remove("suggestions").apply()
    }
}
