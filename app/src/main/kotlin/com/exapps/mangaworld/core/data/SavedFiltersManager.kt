package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import com.exapps.mangaworld.domain.model.SearchFilters
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SavedFilter(
    val id: String,
    val name: String,
    val filters: SearchFilters
)

@Singleton
class SavedFiltersManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("saved_filters", Context.MODE_PRIVATE)
    }

    fun getAll(): List<SavedFilter> {
        val result = mutableListOf<SavedFilter>()
        val all = prefs.all
        all.keys.filter { it.startsWith(FILTER_PREFIX) }.forEach { key ->
            val json = prefs.getString(key, null) ?: return@forEach
            try {
                val obj = JSONObject(json)
                result.add(
                    SavedFilter(
                        id = key.removePrefix(FILTER_PREFIX),
                        name = obj.getString("name"),
                        filters = SearchFilters(
                            query = obj.optString("query", ""),
                            genre = obj.optString("genre", "").ifBlank { null },
                            status = obj.optString("status", "").ifBlank { null }?.let { 
                                com.exapps.mangaworld.domain.model.MangaStatus.from(it) 
                            },
                            sortBy = try { 
                                com.exapps.mangaworld.domain.model.SortBy.valueOf(obj.optString("sortBy", "LATEST")) 
                            } catch (e: Exception) { 
                                com.exapps.mangaworld.domain.model.SortBy.LATEST 
                            }
                        )
                    )
                )
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }
        return result.sortedBy { it.name }
    }

    fun save(name: String, filters: SearchFilters): SavedFilter {
        val id = "filter_${System.currentTimeMillis()}"
        val obj = JSONObject().apply {
            put("name", name)
            put("query", filters.query)
            put("genre", filters.genre ?: "")
            put("status", filters.status?.name ?: "")
            put("sortBy", filters.sortBy.name)
        }
        prefs.edit().putString(FILTER_PREFIX + id, obj.toString()).apply()
        return SavedFilter(id, name, filters)
    }

    fun delete(id: String) {
        prefs.edit().remove(FILTER_PREFIX + id).apply()
    }

    fun rename(id: String, newName: String) {
        val json = prefs.getString(FILTER_PREFIX + id, null) ?: return
        try {
            val obj = JSONObject(json)
            obj.put("name", newName)
            prefs.edit().putString(FILTER_PREFIX + id, obj.toString()).apply()
        } catch (e: Exception) {
            // Skip
        }
    }

    private companion object {
        const val FILTER_PREFIX = "__pf_"
    }
}
