package com.exapps.mangaworld.core.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readingStatsDataStore by preferencesDataStore(name = "reading_stats")

@Singleton
class ReadingStatsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.readingStatsDataStore
    private val totalReadingTimeKey = longPreferencesKey("total_reading_time_ms")
    private val dailyPagesKey = stringPreferencesKey("daily_pages")
    private val dailyTimeKey = stringPreferencesKey("daily_time_ms")
    private val lastReadDateKey = stringPreferencesKey("last_read_date")
    private val currentStreakKey = intPreferencesKey("current_streak")
    private val longestStreakKey = intPreferencesKey("longest_streak")
    private val totalMangaReadKey = intPreferencesKey("total_manga_read")

    val totalReadingTimeMs: Flow<Long> = dataStore.data.map { it[totalReadingTimeKey] ?: 0L }

    val currentStreak: Flow<Int> = dataStore.data.map { it[currentStreakKey] ?: 0 }

    val longestStreak: Flow<Int> = dataStore.data.map { it[longestStreakKey] ?: 0 }

    val totalMangaRead: Flow<Int> = dataStore.data.map { it[totalMangaReadKey] ?: 0 }

    val dailyStats: Flow<List<DailyStat>> = dataStore.data.map { prefs ->
        val pagesJson = prefs[dailyPagesKey] ?: "[]"
        val timeJson = prefs[dailyTimeKey] ?: "[]"
        parseDailyStats(pagesJson, timeJson)
    }

    suspend fun addReadingTime(durationMs: Long) {
        if (durationMs <= 0L) return
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        // Read previousDate AND update atomically inside dataStore.edit
        dataStore.edit { prefs ->
            val previousDate = prefs[lastReadDateKey]
            prefs[totalReadingTimeKey] = (prefs[totalReadingTimeKey] ?: 0L) + durationMs
            prefs[lastReadDateKey] = today
            // Also record daily reading time for the stats chart (use Long to avoid truncation)
            val timeMap = parseLongMap(prefs[dailyTimeKey] ?: "{}")
            timeMap[today] = (timeMap[today] ?: 0L) + durationMs
            prefs[dailyTimeKey] = longMapToJson(timeMap)
            updateStreakInPlace(prefs, today, previousDate)
        }
    }

    suspend fun recordPageRead(pagesRead: Int) {
        if (pagesRead <= 0) return
        dataStore.edit { prefs ->
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val pagesMap = parseMap(prefs[dailyPagesKey] ?: "{}")
            pagesMap[today] = (pagesMap[today] ?: 0) + pagesRead
            prefs[dailyPagesKey] = mapToJson(pagesMap)
        }
    }

    suspend fun incrementMangaRead() {
        dataStore.edit { prefs ->
            prefs[totalMangaReadKey] = (prefs[totalMangaReadKey] ?: 0) + 1
        }
    }

    private fun updateStreakInPlace(prefs: MutablePreferences, today: String, previousDate: String?) {
        val currentStreak = prefs[currentStreakKey] ?: 0
        val longestStreak = prefs[longestStreakKey] ?: 0

        if (previousDate == null || previousDate == today) {
            if (currentStreak == 0) {
                prefs[currentStreakKey] = 1
            }
        } else {
            val lastLocalDate = LocalDate.parse(previousDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val todayLocalDate = LocalDate.parse(today, DateTimeFormatter.ISO_LOCAL_DATE)
            val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(lastLocalDate, todayLocalDate)

            if (daysDiff == 1L) {
                val newStreak = currentStreak + 1
                prefs[currentStreakKey] = newStreak
                if (newStreak > longestStreak) {
                    prefs[longestStreakKey] = newStreak
                }
            } else if (daysDiff > 1L) {
                prefs[currentStreakKey] = 1
            }
        }
    }

    private fun parseDailyStats(pagesJson: String, timeJson: String): List<DailyStat> {
        val pagesMap = parseMap(pagesJson)
        val timeMap = parseLongMap(timeJson)
        val allDates = (pagesMap.keys + timeMap.keys).distinct().sorted()

        return allDates.takeLast(30).map { date ->
            DailyStat(
                date = date,
                pagesRead = pagesMap[date] ?: 0,
                readingTimeMs = timeMap[date] ?: 0L
            )
        }
    }

    /** Prune old daily stats to keep DataStore strings bounded. Called periodically. */
    suspend fun pruneOldStats(keepDays: Int = 60) {
        dataStore.edit { prefs ->
            val cutoff = java.time.LocalDate.now().minusDays(keepDays.toLong()).toString()
            val pagesMap = parseMap(prefs[dailyPagesKey] ?: "{}")
            val timeMap = parseLongMap(prefs[dailyTimeKey] ?: "{}")

            val prunedPages = pagesMap.filterKeys { it >= cutoff }
            val prunedTime = timeMap.filterKeys { it >= cutoff }

            prefs[dailyPagesKey] = mapToJson(prunedPages)
            prefs[dailyTimeKey] = longMapToJson(prunedTime)
        }
    }

    /** Parse a JSON object into a mutable Int map (used for page counts). */
    private fun parseMap(json: String): MutableMap<String, Int> {
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key -> obj.getInt(key) }.toMutableMap()
        } catch (e: Exception) {
            Log.w("ReadingStatsStore", "Failed to parse page stats JSON: ${e.message}")
            mutableMapOf()
        }
    }

    /** Parse a JSON object into a mutable Long map (used for reading time in ms). */
    private fun parseLongMap(json: String): MutableMap<String, Long> {
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key -> obj.getLong(key) }.toMutableMap()
        } catch (e: Exception) {
            try {
                parseMap(json).mapValues { it.value.toLong() }.toMutableMap()
            } catch (e2: Exception) {
                Log.w("ReadingStatsStore", "Failed to parse reading time JSON: ${e2.message}")
                mutableMapOf()
            }
        }
    }

    /** Serialize a String→Int map to JSON. */
    private fun mapToJson(map: Map<String, Int>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    /** Serialize a String→Long map to JSON. */
    private fun longMapToJson(map: Map<String, Long>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}

data class DailyStat(
    val date: String,
    val pagesRead: Int,
    val readingTimeMs: Long
) {
    val readingTimeMinutes: Int get() = (readingTimeMs / 60_000).toInt()
    val displayDate: String get() {
        val parts = date.split("-")
        return if (parts.size == 3) "${parts[1]}/${parts[2]}" else date
    }
}
