package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPolicyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    suspend fun checkAndSendReminders() {
        val favorites = favoriteDao.getFavoritesList()
        val history = historyDao.getAll()

        // Check for inactive users (no reads in 3 days)
        val lastRead = history.maxByOrNull { it.lastReadAt }
        if (lastRead != null) {
            val daysSinceLastRead = TimeUnit.MILLISECONDS.toDays(
                System.currentTimeMillis() - lastRead.lastReadAt
            )
            if (daysSinceLastRead >= 3) {
                sendInactivityReminder(lastRead.title)
            }
        }

        // Check for favorites with new chapters (simplified - in real app would check remote)
        favorites.take(3).forEach { favorite ->
            // In a real implementation, we'd check if there are new chapters
            // For now, we just track the favorites for potential notifications
        }
    }

    fun sendFavoriteUpdateNotification(mangaTitle: String, chapterNumber: String) {
        val intent = AppLaunchIntents.home(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            mangaTitle.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("تحديث جديد: $mangaTitle")
            .setContentText("فصل $chapterNumber متاح الآن")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(
            NOTIFICATION_ID_FAVORITE_UPDATE + mangaTitle.hashCode(),
            notification
        )
    }

    private fun sendInactivityReminder(lastMangaTitle: String) {
        val intent = AppLaunchIntents.home(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("لم نرك منذ فترة!")
            .setContentText("هل تريد المتابعة في قراءة \"$lastMangaTitle\"؟")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_INACTIVITY, notification)
    }

    fun muteMangaNotifications(mangaId: String) {
        // Store muted manga IDs in SharedPreferences
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val muted = prefs.getStringSet("muted_manga", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("muted_manga", muted + mangaId).apply()
    }

    fun unmuteMangaNotifications(mangaId: String) {
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
    }
}
