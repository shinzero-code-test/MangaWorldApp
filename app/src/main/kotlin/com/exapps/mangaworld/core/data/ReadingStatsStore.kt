package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readingStatsDataStore by preferencesDataStore(name = "reading_stats")

@Singleton
class ReadingStatsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.readingStatsDataStore
    private val totalReadingTimeKey = longPreferencesKey("total_reading_time_ms")

    val totalReadingTimeMs: Flow<Long> = dataStore.data.map { it[totalReadingTimeKey] ?: 0L }

    suspend fun addReadingTime(durationMs: Long) {
        if (durationMs <= 0L) return
        dataStore.edit { prefs ->
            prefs[totalReadingTimeKey] = (prefs[totalReadingTimeKey] ?: 0L) + durationMs
        }
    }
}
