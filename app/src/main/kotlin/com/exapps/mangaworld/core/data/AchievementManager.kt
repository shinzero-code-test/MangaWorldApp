package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.goalsDataStore by preferencesDataStore(name = "reading_goals")

data class ReadingGoal(
    val id: String,
    val type: GoalType,
    val targetValue: Int,
    val currentValue: Int = 0,
    val period: GoalPeriod = GoalPeriod.DAILY,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progressPercent: Float get() = if (targetValue > 0) (currentValue.toFloat() / targetValue).coerceIn(0f, 1f) else 0f
    val isCompleted: Boolean get() = currentValue >= targetValue
}

enum class GoalType(val label: String) {
    PAGES_READ("صفحات مقروءة"),
    READING_TIME("وقت القراءة (دقائق)"),
    CHAPTERS_READ("فصول مقروءة")
}

enum class GoalPeriod(val label: String) {
    DAILY("يومي"),
    WEEKLY("أسبوعي"),
    MONTHLY("شهري")
}

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val isUnlocked: Boolean = false,
    val unlockedAt: Long? = null
)

@Singleton
class AchievementManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager
) {
    private val dataStore = context.goalsDataStore
    private val goalsKey = stringPreferencesKey("reading_goals")
    private val achievementsKey = stringPreferencesKey("achievements")
    private val totalPagesReadKey = intPreferencesKey("total_pages_read")
    private val totalChaptersReadKey = intPreferencesKey("total_chapters_read")
    private val lastFirestoreSyncKey = intPreferencesKey("last_firestore_sync")
    private val firestore = FirebaseFirestore.getInstance()

    // Throttle Firestore syncs to max once every 30 minutes
    private val FIRESTORE_SYNC_INTERVAL_MS = 30 * 60 * 1000L

    val goals: Flow<List<ReadingGoal>> = dataStore.data.map { prefs ->
        parseGoals(prefs[goalsKey] ?: "[]")
    }

    val achievements: Flow<List<Achievement>> = dataStore.data.map { prefs ->
        val stored = parseAchievements(prefs[achievementsKey] ?: "[]")
        // Merge with default achievements
        val defaults = getDefaultAchievements()
        defaults.map { default ->
            val storedVersion = stored.find { it.id == default.id }
            storedVersion ?: default
        }
    }

    val totalPagesRead: Flow<Int> = dataStore.data.map { it[totalPagesReadKey] ?: 0 }
    val totalChaptersRead: Flow<Int> = dataStore.data.map { it[totalChaptersReadKey] ?: 0 }

    suspend fun createGoal(type: GoalType, targetValue: Int, period: GoalPeriod): ReadingGoal {
        val goal = ReadingGoal(
            id = "goal_${System.currentTimeMillis()}",
            type = type,
            targetValue = targetValue,
            period = period
        )
        dataStore.edit { prefs ->
            val current = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
            current.add(goal)
            prefs[goalsKey] = goalsToJson(current)
        }
        return goal
    }

    suspend fun updateGoalProgress(goalId: String, increment: Int) {
        dataStore.edit { prefs ->
            val goals = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
            val index = goals.indexOfFirst { it.id == goalId }
            if (index >= 0) {
                val goal = goals[index]
                goals[index] = goal.copy(currentValue = goal.currentValue + increment)
                prefs[goalsKey] = goalsToJson(goals)
            }
        }
    }

    suspend fun deleteGoal(goalId: String) {
        dataStore.edit { prefs ->
            val goals = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
            goals.removeAll { it.id == goalId }
            prefs[goalsKey] = goalsToJson(goals)
        }
    }

    suspend fun recordPageRead(pages: Int = 1) {
        dataStore.edit { prefs ->
            prefs[totalPagesReadKey] = (prefs[totalPagesReadKey] ?: 0) + pages
        }
        checkAchievements()
    }

    suspend fun recordChapterRead() {
        dataStore.edit { prefs ->
            prefs[totalChaptersReadKey] = (prefs[totalChaptersReadKey] ?: 0) + 1
        }
        checkAchievements()
    }

    suspend fun checkAndUnlockAchievements(pagesToday: Int, chaptersToday: Int, currentStreak: Int) {
        dataStore.edit { prefs ->
            val achievements = parseAchievements(prefs[achievementsKey] ?: "[]").toMutableList()
            val now = System.currentTimeMillis()

            // Check each achievement condition
            val updates = mutableListOf<Achievement>()

            achievements.forEach { achievement ->
                if (!achievement.isUnlocked) {
                    val shouldUnlock = when (achievement.id) {
                        "first_chapter" -> chaptersToday >= 1
                        "bookworm" -> (prefs[totalPagesReadKey] ?: 0) >= 100
                        "speed_reader" -> pagesToday >= 50
                        "streak_7" -> currentStreak >= 7
                        "streak_30" -> currentStreak >= 30
                        "manga_master" -> (prefs[totalChaptersReadKey] ?: 0) >= 100
                        else -> false
                    }
                    if (shouldUnlock) {
                        updates.add(achievement.copy(isUnlocked = true, unlockedAt = now))
                    }
                }
            }

            if (updates.isNotEmpty()) {
                val updatedAchievements = achievements.map { stored ->
                    updates.find { it.id == stored.id } ?: stored
                }
                prefs[achievementsKey] = achievementsToJson(updatedAchievements)
            }
        }
    }

    private suspend fun checkAchievements() {
        val prefs = dataStore.data.first()
        val totalPages = prefs[totalPagesReadKey] ?: 0
        val totalChapters = prefs[totalChaptersReadKey] ?: 0
        val storedAchievements = parseAchievements(prefs[achievementsKey] ?: "[]").toMutableList()
        val defaults = getDefaultAchievements()
        val now = System.currentTimeMillis()
        var changed = false

        // Ensure all default achievements exist in the stored list
        val mergedAchievements = defaults.map { default ->
            val existing = storedAchievements.find { it.id == default.id }
            existing ?: default
        }.toMutableList()

        // Check unlock conditions
        mergedAchievements.forEachIndexed { index, achievement ->
            if (!achievement.isUnlocked) {
                val shouldUnlock = when (achievement.id) {
                    "first_chapter" -> totalChapters >= 1
                    "bookworm" -> totalPages >= 100
                    "speed_reader" -> totalPages >= 50
                    "streak_7" -> false // streak checked separately via checkAndUnlockAchievements
                    "streak_30" -> false
                    "manga_master" -> totalChapters >= 100
                    else -> false
                }
                if (shouldUnlock) {
                    mergedAchievements[index] = achievement.copy(isUnlocked = true, unlockedAt = now)
                    changed = true
                }
            }
        }

        // Single DataStore write for all updates (achievements + goals)
        val goals = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
        var goalsChanged = false
        goals.forEachIndexed { index, goal ->
            if (goal.isActive && !goal.isCompleted) {
                val newValue = when (goal.type) {
                    GoalType.PAGES_READ -> totalPages
                    GoalType.CHAPTERS_READ -> totalChapters
                    GoalType.READING_TIME -> {
                        // Wire to actual reading time from ReadingStatsStore
                        runCatching {
                            val statsPrefs = context.getSharedPreferences("reading_stats_prefs", 0)
                            // Reading time is tracked separately; use a best-effort read
                            0 // TODO: inject ReadingStatsStore for proper integration
                        }.getOrDefault(0)
                    }
                }
                if (newValue != goal.currentValue) {
                    goals[index] = goal.copy(currentValue = newValue)
                    goalsChanged = true
                }
            }
        }

        dataStore.edit { store ->
            store[totalPagesReadKey] = totalPages
            store[totalChaptersReadKey] = totalChapters
            if (changed) {
                store[achievementsKey] = achievementsToJson(mergedAchievements)
            }
            if (goalsChanged) {
                store[goalsKey] = goalsToJson(goals)
            }
        }

        // Throttled Firestore sync — max once every 30 minutes
        val lastSync = prefs[lastFirestoreSyncKey] ?: 0L
        if (now - lastSync > FIRESTORE_SYNC_INTERVAL_MS) {
            dataStore.edit { it[lastFirestoreSyncKey] = now }
            syncToFirestore()
        }
    }

    private suspend fun syncToFirestore() {
        try {
            val uid = sessionManager.ensureGuestSession() ?: return
            val prefs = dataStore.data.first()
            val data = mapOf(
                "totalPagesRead" to (prefs[totalPagesReadKey] ?: 0),
                "totalChaptersRead" to (prefs[totalChaptersReadKey] ?: 0),
                "goals" to (prefs[goalsKey] ?: "[]"),
                "achievements" to (prefs[achievementsKey] ?: "[]"),
                "lastUpdated" to System.currentTimeMillis()
            )
            firestore.collection("user_achievements")
                .document(uid)
                .set(data, SetOptions.merge())
                .await()
        } catch (_: Exception) {
            // Silently fail — Firestore sync is best-effort
        }
    }

    private fun getDefaultAchievements(): List<Achievement> = listOf(
        Achievement("first_chapter", "أول فصل", "اقرأ أول فصل", "📖"),
        Achievement("bookworm", "دودة الكتب", "اقرأ 100 صفحة", "📚"),
        Achievement("speed_reader", "قارئ سريع", "اقرأ 50 صفحة في يوم واحد", "⚡"),
        Achievement("streak_7", "سلسلة أسبوعية", "حافظ على سلسلة قراءة 7 أيام", "🔥"),
        Achievement("streak_30", "سلسلة شهرية", "حافظ على سلسلة قراءة 30 يوم", "💪"),
        Achievement("manga_master", "أسطورة المانجا", "اقرأ 100 فصل", "🏆")
    )

    private fun parseGoals(json: String): List<ReadingGoal> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                ReadingGoal(
                    id = obj.getString("id"),
                    type = GoalType.valueOf(obj.getString("type")),
                    targetValue = obj.getInt("targetValue"),
                    currentValue = obj.optInt("currentValue", 0),
                    period = GoalPeriod.valueOf(obj.optString("period", "DAILY")),
                    isActive = obj.optBoolean("isActive", true),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun goalsToJson(goals: List<ReadingGoal>): String {
        val arr = JSONArray()
        goals.forEach { goal ->
            val obj = JSONObject().apply {
                put("id", goal.id)
                put("type", goal.type.name)
                put("targetValue", goal.targetValue)
                put("currentValue", goal.currentValue)
                put("period", goal.period.name)
                put("isActive", goal.isActive)
                put("createdAt", goal.createdAt)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseAchievements(json: String): List<Achievement> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Achievement(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    description = obj.getString("description"),
                    icon = obj.getString("icon"),
                    isUnlocked = obj.optBoolean("isUnlocked", false),
                    unlockedAt = if (obj.has("unlockedAt")) obj.getLong("unlockedAt") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun achievementsToJson(achievements: List<Achievement>): String {
        val arr = JSONArray()
        achievements.forEach { achievement ->
            val obj = JSONObject().apply {
                put("id", achievement.id)
                put("title", achievement.title)
                put("description", achievement.description)
                put("icon", achievement.icon)
                put("isUnlocked", achievement.isUnlocked)
                if (achievement.unlockedAt != null) put("unlockedAt", achievement.unlockedAt)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}
