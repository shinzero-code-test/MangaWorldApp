package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetSnapshotDataStore by preferencesDataStore(name = "widget_snapshots")

@Singleton
class WidgetSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.widgetSnapshotDataStore

    private val snapshotKey = stringPreferencesKey("remote_widgets_snapshot")
    private val updatedAtKey = longPreferencesKey("remote_widgets_updated_at")

    suspend fun readRemoteSnapshot(): RemoteWidgetsSnapshot? {
        val prefs = dataStore.data.first()
        val json = prefs[snapshotKey] ?: return null
        return runCatching { json.toRemoteWidgetsSnapshot() }.getOrNull()
    }

    suspend fun saveRemoteSnapshot(snapshot: RemoteWidgetsSnapshot) {
        dataStore.edit { prefs ->
            prefs[snapshotKey] = snapshot.toJson().toString()
            prefs[updatedAtKey] = snapshot.generatedAt
        }
    }

    suspend fun lastUpdatedAt(): Long = dataStore.data.first()[updatedAtKey] ?: 0L
}

private fun RemoteWidgetsSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("generatedAt", generatedAt)
    put("recommendation", recommendation?.toJson())
    put("trending", trending?.toJson())
    put("latestUpdates", JSONArray().apply { latestUpdates.forEach { put(it.toJson()) } })
}

private fun WidgetMangaEntry.toJson(): JSONObject = JSONObject().apply {
    put("mangaId", mangaId)
    put("sourceId", sourceId)
    put("slug", slug)
    put("title", title)
    put("coverUrl", coverUrl)
    put("subtitle", subtitle)
}

private fun WidgetLatestUpdateEntry.toJson(): JSONObject = JSONObject().apply {
    put("mangaId", mangaId)
    put("sourceId", sourceId)
    put("mangaSlug", mangaSlug)
    put("mangaTitle", mangaTitle)
    put("coverUrl", coverUrl)
    put("chapterLabel", chapterLabel)
    put("chapterUrl", chapterUrl)
    put("publishedAt", publishedAt)
    put("timeAgo", timeAgo)
}

private fun String.toRemoteWidgetsSnapshot(): RemoteWidgetsSnapshot {
    val obj = JSONObject(this)
    return RemoteWidgetsSnapshot(
        generatedAt = obj.optLong("generatedAt"),
        recommendation = obj.optJSONObject("recommendation")?.toWidgetMangaEntry(),
        trending = obj.optJSONObject("trending")?.toWidgetMangaEntry(),
        latestUpdates = obj.optJSONArray("latestUpdates")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toWidgetLatestUpdateEntry() }
        }.orEmpty()
    )
}

private fun JSONObject.toWidgetMangaEntry() = WidgetMangaEntry(
    mangaId = getString("mangaId"),
    sourceId = getString("sourceId"),
    slug = getString("slug"),
    title = getString("title"),
    coverUrl = optString("coverUrl"),
    subtitle = optString("subtitle").ifBlank { null }
)

private fun JSONObject.toWidgetLatestUpdateEntry() = WidgetLatestUpdateEntry(
    mangaId = getString("mangaId"),
    sourceId = getString("sourceId"),
    mangaSlug = getString("mangaSlug"),
    mangaTitle = getString("mangaTitle"),
    coverUrl = optString("coverUrl"),
    chapterLabel = getString("chapterLabel"),
    chapterUrl = getString("chapterUrl"),
    publishedAt = optLong("publishedAt").takeIf { it > 0L },
    timeAgo = optString("timeAgo").ifBlank { null }
)
