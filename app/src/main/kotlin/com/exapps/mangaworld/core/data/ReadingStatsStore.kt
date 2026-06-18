package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        dataStore.edit { prefs ->
            prefs[totalReadingTimeKey] = (prefs[totalReadingTimeKey] ?: 0L) + durationMs
            prefs[lastReadDateKey] = today
        }
        updateStreak(today)
    }

    suspend fun recordPageRead(pagesRead: Int) {
        if (pagesRead <= 0) return
        dataStore.edit { prefs ->
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val pagesMap = parseMap(prefs[dailyPagesKey] ?: "{}")
            pagesMap[today] = (pagesMap[today] ?: 0) + pagesRead
            prefs[dailyPagesKey] = pagesMap.toString()
        }
    }

    suspend fun incrementMangaRead() {
        dataStore.edit { prefs ->
            prefs[totalMangaReadKey] = (prefs[totalMangaReadKey] ?: 0) + 1
        }
    }

    private suspend fun updateStreak(today: String) {
        dataStore.edit { prefs ->
            val lastDate = prefs[lastReadDateKey]
            val currentStreak = prefs[currentStreakKey] ?: 0
            val longestStreak = prefs[longestStreakKey] ?: 0

            if (lastDate == null || lastDate == today) {
                // First read or same day
                if (currentStreak == 0) {
                    prefs[currentStreakKey] = 1
                }
            } else {
                val lastLocalDate = LocalDate.parse(lastDate, DateTimeFormatter.ISO_LOCAL_DATE)
                val todayLocalDate = LocalDate.parse(today, DateTimeFormatter.ISO_LOCAL_DATE)
                val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(lastLocalDate, todayLocalDate)

                if (daysDiff == 1L) {
                    // Consecutive day
                    val newStreak = currentStreak + 1
                    prefs[currentStreakKey] = newStreak
                    if (newStreak > longestStreak) {
                        prefs[longestStreakKey] = newStreak
                    }
                } else if (daysDiff > 1L) {
                    // Streak broken
                    prefs[currentStreakKey] = 1
                }
            }
        }
    }

    private fun parseDailyStats(pagesJson: String, timeJson: String): List<DailyStat> {
        val pagesMap = parseMap(pagesJson)
        val timeMap = parseMap(timeJson)
        val allDates = (pagesMap.keys + timeMap.keys).distinct().sorted()

        return allDates.takeLast(30).map { date ->
            DailyStat(
                date = date,
                pagesRead = pagesMap[date] ?: 0,
                readingTimeMs = (timeMap[date] ?: 0).toLong()
            )
        }
    }

    private fun parseMap(json: String): MutableMap<String, Int> {
        return try {
            val cleaned = json.trim().removePrefix("{").removeSuffix("}")
            if (cleaned.isBlank()) return mutableMapOf()
            cleaned.split(",").associate { entry ->
                val parts = entry.split(":")
                parts[0].trim().removeSurrounding("\"") to (parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0)
            }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
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
