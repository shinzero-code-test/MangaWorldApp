package com.exapps.mangaworld.core.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.domain.model.NotificationDeliveryMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPolicyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val settingsRepository: SettingsRepository
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        // Create a low-importance channel for reminders (separate from CLOUD_CHANNEL_ID which is HIGH)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "تذكيرات القراءة",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "تذكيرات غير مزعجة للمتابعة" }
            )
        }
    }

    suspend fun checkAndSendReminders() {
        // Respect the user's notification preference
        val settings = settingsRepository.getAppSettings().first()
        if (!settings.enableNotifications) return
        // Respect delivery mode — only INSTANT notifications are sent immediately
        if (settings.notificationDeliveryMode != NotificationDeliveryMode.INSTANT) return

        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

        // Throttle: only send inactivity reminders once per 24 hours
        val lastInactivitySent = prefs.getLong("last_inactivity_sent", 0L)
        val now = System.currentTimeMillis()
        if (now - lastInactivitySent < 24 * 60 * 60 * 1000L) return

        // Suppress inactivity reminder if the app was just opened (within 5 minutes)
        // This prevents the awkward UX of getting a "we miss you" notification immediately after launching
        val appStartTime = prefs.getLong("app_start_time", 0L)
        if (now - appStartTime < 5 * 60 * 1000L) return

        // Use optimized query instead of loading entire history
        val lastRead = historyDao.getLatest()

        if (lastRead != null) {
            val daysSinceLastRead = TimeUnit.MILLISECONDS.toDays(
                now - lastRead.lastReadAt
            )
            if (daysSinceLastRead >= 3) {
                sendInactivityReminder(lastRead.title)
                prefs.edit().putLong("last_inactivity_sent", now).apply()
            }
        }
    }

    private fun sendInactivityReminder(lastMangaTitle: String) {
        val intent = AppLaunchIntents.home(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use low-importance reminder channel (created in init block)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(com.exapps.mangaworld.R.string.reminder_title))
            .setContentText(context.getString(com.exapps.mangaworld.R.string.reminder_continue_reading, lastMangaTitle))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_INACTIVITY, notification)
    }

    // Serializes read-modify-write on the muted_manga StringSet (M-review).
    private val muteLock = Any()

    fun muteMangaNotifications(mangaId: String) = synchronized(muteLock) {
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val muted = prefs.getStringSet("muted_manga", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("muted_manga", muted + mangaId).apply()
    }

    fun unmuteMangaNotifications(mangaId: String) = synchronized(muteLock) {
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val muted = prefs.getStringSet("muted_manga", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("muted_manga", muted - mangaId).apply()
    }

    fun isMangaMuted(mangaId: String): Boolean {
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val muted = prefs.getStringSet("muted_manga", emptySet()) ?: emptySet()
        return mangaId in muted
    }

    companion object {
        private const val NOTIFICATION_ID_FAVORITE_UPDATE = 5000
        private const val NOTIFICATION_ID_INACTIVITY = 5001
        private const val REMINDER_REQUEST_CODE = 1001
        private const val REMINDER_CHANNEL_ID = "reminder_channel"
    }
}
