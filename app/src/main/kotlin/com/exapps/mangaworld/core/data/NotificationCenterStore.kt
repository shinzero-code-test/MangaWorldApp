package com.exapps.mangaworld.core.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Single writer for the `local_notifications` SharedPreferences ring buffer.
 *
 * The worker (ChapterUpdateChecker) and the Notification Center screen both
 * performed read→mutate→write cycles; concurrent runs silently dropped one
 * side's update (M-review). All mutations now funnel through [update], which
 * holds a process-wide mutex across the whole read-modify-write.
 */
object NotificationCenterStore {
    const val PREFS_FILE = "local_notifications"
    const val KEY_NOTIFICATIONS = "notifications"

    private val mutex = Mutex()

    suspend fun <T> update(context: Context, transform: (JSONArray) -> T): T = mutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val result = transform(arr)
        prefs.edit().putString(KEY_NOTIFICATIONS, arr.toString()).apply()
        result
    }
}
