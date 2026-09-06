package com.exapps.mangaworld.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val sessionManager: FirebaseSessionManager,
    private val readingStatsStore: ReadingStatsStore
) {
    private val dataStore = context.goalsDataStore
    private val goalsKey = stringPreferencesKey("reading_goals")
    private val achievementsKey = stringPreferencesKey("achievements")
    private val totalPagesReadKey = intPreferencesKey("total_pages_read")
    private val totalChaptersReadKey = intPreferencesKey("total_chapters_read")
    private val lastFirestoreSyncKey = longPreferencesKey("last_firestore_sync")

    @Volatile private var lastAchievementCheckMs: Long = 0L

    /** Emits newly unlocked achievements for UI notification. */
    private val _achievementUnlocked = MutableSharedFlow<Achievement>(extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val achievementUnlocked: Flow<Achievement> = _achievementUnlocked

    /** Collect pending achievement unlocks and show notification. */
    suspend fun collectAndNotifyUnlocks() {
        achievementUnlocked.collect { achievement ->
            android.util.Log.i("AchievementManager", "Achievement unlocked: ${achievement.title} (${achievement.icon})")
        }
    }
    private val firestore = FirebaseFirestore.getInstance()

    // Throttle Firestore syncs to max once every 30 minutes
    private val FIRESTORE_SYNC_INTERVAL_MS = 30 * 60 * 1000L

    val goals: Flow<List<ReadingGoal>> = dataStore.data.map { prefs ->
        parseGoals(prefs[goalsKey] ?: "[]")
    }

    /** Count of unlocked achievements, read from DataStore. */
    suspend fun getUnlockedAchievementCount(): Int {
        val prefs = dataStore.data.first()
        val achievements = parseAchievements(prefs[achievementsKey] ?: "[]")
        val defaults = getDefaultAchievements()
        val merged = defaults.map { default -> achievements.find { it.id == default.id } ?: default }
        return merged.count { it.isUnlocked }
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

    /** Maximum target values per goal type to prevent unachievable goals. */
    private val maxTargetValues = mapOf(
        GoalType.PAGES_READ to 10_000,
        GoalType.CHAPTERS_READ to 1_000,
        GoalType.READING_TIME to 50_000  // 50,000 minutes ≈ 833 hours
    )

    suspend fun createGoal(type: GoalType, targetValue: Int, period: GoalPeriod): ReadingGoal {
        val cappedTarget = targetValue.coerceIn(1, maxTargetValues[type] ?: 10_000)
        val goal = ReadingGoal(
            id = "goal_${System.currentTimeMillis()}",
            type = type,
            targetValue = cappedTarget,
            period = period
        )
        dataStore.edit { prefs ->
            val current = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
            current.add(goal)
            prefs[goalsKey] = goalsToJson(current)
        }
        return goal
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
        // Throttle: only check achievements every 5 seconds to avoid per-page overhead
        val now = System.currentTimeMillis()
        if (now - lastAchievementCheckMs < 5_000L) return
        lastAchievementCheckMs = now
        checkAchievements()
    }

    suspend fun recordChapterRead() {
        dataStore.edit { prefs ->
            prefs[totalChaptersReadKey] = (prefs[totalChaptersReadKey] ?: 0) + 1
        }
        checkAchievements()
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
        val currentStreak = readingStatsStore.currentStreak.first()
        mergedAchievements.forEachIndexed { index, achievement ->
            if (!achievement.isUnlocked) {
                val shouldUnlock = when (achievement.id) {
                    "first_chapter" -> totalChapters >= 1
                    "bookworm" -> totalPages >= 100
                    "speed_reader" -> {
                        // 50 pages in a single day
                        readingStatsStore.dailyStats.first().any { it.pagesRead >= 50 }
                    }
                    "streak_7" -> currentStreak >= 7
                    "streak_30" -> currentStreak >= 30
                    "manga_master" -> totalChapters >= 100
                    else -> false
                }
                if (shouldUnlock) {
                    mergedAchievements[index] = achievement.copy(isUnlocked = true, unlockedAt = now)
                    changed = true
                    _achievementUnlocked.tryEmit(achievement)
                }
            }
        }

        // Single DataStore write for all updates (achievements + goals)
        val goals = parseGoals(prefs[goalsKey] ?: "[]").toMutableList()
        var goalsChanged = false
        goals.forEachIndexed { index, goal ->
            if (goal.isActive) {
                val newValue = when (goal.type) {
                    GoalType.PAGES_READ -> getPeriodPages(goal.period)
                    GoalType.CHAPTERS_READ -> getPeriodChapters(goal.period)
                    GoalType.READING_TIME -> getPeriodReadingTimeMinutes(goal.period)
                }
                if (goal.isCompleted) {
                    // Auto-archive: mark completed goals as inactive so they stop being re-checked
                    goals[index] = goal.copy(isActive = false)
                    goalsChanged = true
                } else if (newValue != goal.currentValue) {
                    goals[index] = goal.copy(currentValue = newValue)
                    goalsChanged = true
                }
            }
        }

        // Write back ONLY what this check changed. Totals are intentionally NOT
        // rewritten here: recordPageRead/recordChapterRead already incremented
        // them in their own serialized edit blocks, and this snapshot predates
        // them — writing the stale values back would silently drop increments
        // from concurrent record calls that landed in between (lost update).
        dataStore.edit { store ->
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
            val syncSuccess = syncToFirestore()
            if (syncSuccess) {
                dataStore.edit { it[lastFirestoreSyncKey] = now }
            }
        }
    }

    /**
     * Import achievements and goals from Firestore data.
     * Used during pullRemoteSnapshot to restore data after device switch or data clear.
     * Only overwrites local data if remote data is newer or local is empty.
     */
    suspend fun importFromFirestore(totalPagesRead: Int, totalChaptersRead: Int, goalsJson: String, achievementsJson: String) {
        dataStore.edit { prefs ->
            val localPages = prefs[totalPagesReadKey] ?: 0
            val localChapters = prefs[totalChaptersReadKey] ?: 0
            if (totalPagesRead > localPages) prefs[totalPagesReadKey] = totalPagesRead
            if (totalChaptersRead > localChapters) prefs[totalChaptersReadKey] = totalChaptersRead
            val localGoals = prefs[goalsKey] ?: "[]"
            if (localGoals == "[]" && goalsJson != "[]") prefs[goalsKey] = goalsJson
            val localAchievements = parseAchievements(prefs[achievementsKey] ?: "[]")
            val remoteAchievements = parseAchievements(achievementsJson)
            val merged = mutableListOf<Achievement>()
            val mergedIds = mutableSetOf<String>()
            for (a in remoteAchievements) { if (a.isUnlocked) { merged.add(a); mergedIds.add(a.id) } }
            for (a in localAchievements) {
                if (a.id !in mergedIds) merged.add(a)
                else if (a.isUnlocked) { val ex = merged.first { it.id == a.id }; if ((a.unlockedAt ?: 0L) > (ex.unlockedAt ?: 0L)) { merged.remove(ex); merged.add(a) } }
            }
            val defaults = getDefaultAchievements()
            prefs[achievementsKey] = achievementsToJson(defaults.map { d -> merged.find { it.id == d.id } ?: d })
        }
    }

    /**
     * Calculate badge based on reading progress and achievements.
     * Used by FirebaseCommunityRepository to update the user's badge.
     *
     * Badge tiers (7 levels): Beginner → Page Turner → Chapter Hunter →
     * Manga Enthusiast → Shonen Specialist → Avid Reader → Pirate King
     *
     * NOTE: These are distinct from FirebaseUserInsightsCoordinator's engagement
     * tiers (new/warming/active/avid), which are analytics-only user properties
     * for cohort analysis. Badge tiers are profile-facing achievement indicators.
     */
    suspend fun calculateBadge(): String {
        val prefs = dataStore.data.first()
        val totalPages = prefs[totalPagesReadKey] ?: 0
        val totalChapters = prefs[totalChaptersReadKey] ?: 0
        val achievements = parseAchievements(prefs[achievementsKey] ?: "[]")
        val unlockedCount = achievements.count { it.isUnlocked }

        return when {
            totalChapters >= 1000 || unlockedCount >= 6 -> "Pirate King"
            totalChapters >= 400 || unlockedCount >= 5 -> "Avid Reader"
            totalChapters >= 150 || unlockedCount >= 4 -> "Shonen Specialist"
            totalChapters >= 50 || unlockedCount >= 3 -> "Manga Enthusiast"
            totalChapters >= 10 || unlockedCount >= 2 -> "Chapter Hunter"
            totalPages >= 50 -> "Page Turner"
            else -> "Beginner"
        }
    }

    /**
     * Get achievements and goals as a Map for Firestore sync.
     */
    suspend fun syncToFirestoreMap(): Map<String, Any> {
        val prefs = dataStore.data.first()
        return mapOf(
            "totalPagesRead" to (prefs[totalPagesReadKey] ?: 0),
            "totalChaptersRead" to (prefs[totalChaptersReadKey] ?: 0),
            "goals" to (prefs[goalsKey] ?: "[]"),
            "achievements" to (prefs[achievementsKey] ?: "[]"),
            "lastUpdated" to System.currentTimeMillis()
        )
    }

    private suspend fun syncToFirestore(): Boolean {
        return try {
            val uid = sessionManager.ensureFirebaseSession() ?: return false
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
            true
        } catch (_: Exception) {
            false
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

    /** Get pages read in the current period (daily/weekly/monthly). */
    private suspend fun getPeriodPages(period: GoalPeriod): Int {
        val dailyStats = readingStatsStore.dailyStats.first()
        val today = java.time.LocalDate.now()
        return when (period) {
            GoalPeriod.DAILY -> dailyStats.filter { it.date == today.toString() }.sumOf { it.pagesRead }
            GoalPeriod.WEEKLY -> {
                val weekStart = today.minusDays(today.dayOfWeek.value.toLong() - 1)
                dailyStats.filter { java.time.LocalDate.parse(it.date) >= weekStart }.sumOf { it.pagesRead }
            }
            GoalPeriod.MONTHLY -> {
                val monthStart = today.withDayOfMonth(1)
                dailyStats.filter { java.time.LocalDate.parse(it.date) >= monthStart }.sumOf { it.pagesRead }
            }
        }
    }

    /** Get chapters read in the current period (estimated from pages). */
    private suspend fun getPeriodChapters(period: GoalPeriod): Int {
        // Chapters are tracked via daily stats pages — approximate by dividing avg pages per chapter
        val pages = getPeriodPages(period)
        return (pages / 20).coerceAtLeast(0) // ~20 pages per chapter estimate
    }

    /** Get reading time in minutes for the current period. */
    private suspend fun getPeriodReadingTimeMinutes(period: GoalPeriod): Int {
        val dailyStats = readingStatsStore.dailyStats.first()
        val today = java.time.LocalDate.now()
        return when (period) {
            GoalPeriod.DAILY -> dailyStats.filter { it.date == today.toString() }.sumOf { it.readingTimeMinutes }
            GoalPeriod.WEEKLY -> {
                val weekStart = today.minusDays(today.dayOfWeek.value.toLong() - 1)
                dailyStats.filter { java.time.LocalDate.parse(it.date) >= weekStart }.sumOf { it.readingTimeMinutes }
            }
            GoalPeriod.MONTHLY -> {
                val monthStart = today.withDayOfMonth(1)
                dailyStats.filter { java.time.LocalDate.parse(it.date) >= monthStart }.sumOf { it.readingTimeMinutes }
            }
        }
    }
}
