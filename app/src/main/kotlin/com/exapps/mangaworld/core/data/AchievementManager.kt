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
