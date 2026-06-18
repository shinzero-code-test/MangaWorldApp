package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.collectionsDataStore by preferencesDataStore(name = "manga_collections")

data class MangaCollection(
    val id: String,
    val name: String,
    val description: String = "",
    val mangaIds: List<String> = emptyList(),
    val isPublic: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Singleton
class CollectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.collectionsDataStore
    private val collectionsKey = stringPreferencesKey("collections")
    private val nextIdKey = intPreferencesKey("next_collection_id")

    val collections: Flow<List<MangaCollection>> = dataStore.data.map { prefs ->
        val json = prefs[collectionsKey] ?: "[]"
        parseCollections(json)
    }

    suspend fun createCollection(name: String, description: String = ""): MangaCollection {
        val id = generateId()
        val collection = MangaCollection(
            id = id,
            name = name,
            description = description
        )
        addCollection(collection)
        return collection
    }

    suspend fun updateCollection(id: String, name: String? = null, description: String? = null, isPublic: Boolean? = null) {
        dataStore.edit { prefs ->
            val collections = parseCollections(prefs[collectionsKey] ?: "[]").toMutableList()
            val index = collections.indexOfFirst { it.id == id }
            if (index >= 0) {
                val existing = collections[index]
                collections[index] = existing.copy(
                    name = name ?: existing.name,
                    description = description ?: existing.description,
                    isPublic = isPublic ?: existing.isPublic,
                    updatedAt = System.currentTimeMillis()
                )
                prefs[collectionsKey] = collectionsToJson(collections)
            }
        }
    }

    suspend fun deleteCollection(id: String) {
        dataStore.edit { prefs ->
            val collections = parseCollections(prefs[collectionsKey] ?: "[]").toMutableList()
            collections.removeAll { it.id == id }
            prefs[collectionsKey] = collectionsToJson(collections)
        }
    }

    suspend fun addMangaToCollection(collectionId: String, mangaId: String) {
        dataStore.edit { prefs ->
            val collections = parseCollections(prefs[collectionsKey] ?: "[]").toMutableList()
            val index = collections.indexOfFirst { it.id == collectionId }
            if (index >= 0) {
                val existing = collections[index]
                if (mangaId !in existing.mangaIds) {
                    collections[index] = existing.copy(
                        mangaIds = existing.mangaIds + mangaId,
                        updatedAt = System.currentTimeMillis()
                    )
                    prefs[collectionsKey] = collectionsToJson(collections)
                }
            }
        }
    }

    suspend fun removeMangaFromCollection(collectionId: String, mangaId: String) {
        dataStore.edit { prefs ->
            val collections = parseCollections(prefs[collectionsKey] ?: "[]").toMutableList()
            val index = collections.indexOfFirst { it.id == collectionId }
            if (index >= 0) {
                val existing = collections[index]
                collections[index] = existing.copy(
                    mangaIds = existing.mangaIds.filter { it != mangaId },
                    updatedAt = System.currentTimeMillis()
                )
                prefs[collectionsKey] = collectionsToJson(collections)
            }
        }
    }

    suspend fun getCollectionsForManga(mangaId: String): List<MangaCollection> {
        return collections.first().filter { mangaId in it.mangaIds }
    }

    private suspend fun addCollection(collection: MangaCollection) {
        dataStore.edit { prefs ->
            val collections = parseCollections(prefs[collectionsKey] ?: "[]").toMutableList()
            collections.add(collection)
            prefs[collectionsKey] = collectionsToJson(collections)
        }
    }

    private fun generateId(): String {
        return "col_${UUID.randomUUID().toString().take(8)}"
    }

    private fun parseCollections(json: String): List<MangaCollection> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val mangaIdsArr = obj.optJSONArray("mangaIds")
                val mangaIds = if (mangaIdsArr != null) {
                    (0 until mangaIdsArr.length()).map { j -> mangaIdsArr.getString(j) }
                } else {
                    emptyList()
                }
                MangaCollection(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    mangaIds = mangaIds,
                    isPublic = obj.optBoolean("isPublic", false),
                    createdAt = obj.optLong("createdAt", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun collectionsToJson(collections: List<MangaCollection>): String {
        val arr = JSONArray()
        collections.forEach { col ->
            val obj = JSONObject().apply {
                put("id", col.id)
                put("name", col.name)
                put("description", col.description)
                put("mangaIds", JSONArray(col.mangaIds))
                put("isPublic", col.isPublic)
                put("createdAt", col.createdAt)
                put("updatedAt", col.updatedAt)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}
