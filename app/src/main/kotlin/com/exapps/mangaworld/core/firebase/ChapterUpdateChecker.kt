package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local chapter update detector — checks sources for new chapters
 * for favorited manga and shows notifications without FCM.
 *
 * Runs on app startup and periodically via WorkManager.
 */
@Singleton
class ChapterUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs by lazy {
        context.getSharedPreferences("chapter_update_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Check all sources for new chapters on favorited manga.
     * Should be called on app startup and periodically.
     */
    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getAppSettings().first()
        if (!settings.enableNotifications) return@withContext

        // Throttle: check at most once per 2 hours
        val lastCheck = prefs.getLong("last_update_check", 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < 2 * 60 * 60 * 1000L) return@withContext
        // commit() ensures the throttle timestamp is persisted before network calls begin;
        // apply() could be lost on process death, causing repeated checks
        prefs.edit().putLong("last_update_check", now).commit()

        val favorites = favoriteDao.getFavoritesList()
        if (favorites.isEmpty()) return@withContext

        // Group favorites by source for efficient checking
        val favoritesBySource = favorites.groupBy { it.sourceId }
        val newChapters = mutableListOf<Pair<String, String>>() // (mangaTitle, chapterInfo)

        for ((sourceId, sourceFavorites) in favoritesBySource) {
            if (sourceId !in settings.enabledSources) continue

            try {
                val source = MangaSource.fromId(sourceId)
                val homeData = mangaRepository.getHomeData(source).getOrDefault(
                    com.exapps.mangaworld.domain.model.HomeData()
                )

                for (favorite in sourceFavorites) {
                    // Find chapters in home data that belong to this manga
                    val latestChapters = homeData.latestChapters.filter {
                        it.mangaId == favorite.mangaId || it.mangaSlug == favorite.slug
                    }

                    if (latestChapters.isNotEmpty()) {
                        // Compare the number of latest chapters fetched from the source
                        // against the count stored from the previous check
                        val fetchedCount = latestChapters.size
                        val lastKnownCount = prefs.getInt("count_${favorite.mangaId}", -1)
                        if (lastKnownCount == -1) {
                            // First time seeing this manga — store baseline, don't notify
                            prefs.edit().putInt("count_${favorite.mangaId}", fetchedCount).apply()
                        } else if (fetchedCount > lastKnownCount) {
                            val diff = fetchedCount - lastKnownCount
                            newChapters.add(favorite.title to "$diff فصل${if (diff > 1) " جديدة" else " جديد"}")
                            prefs.edit().putInt("count_${favorite.mangaId}", fetchedCount).apply()
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip failed sources silently
            }
        }

        // Show notification if new chapters found
        if (newChapters.isNotEmpty()) {
            showNewChaptersNotification(newChapters)
        }
    }

    /**
     * Snapshot current chapter counts without showing notifications.
     * Call this to initialize the baseline for future comparisons.
     */
    suspend fun snapshotCurrentCounts() = withContext(Dispatchers.IO) {
        val favorites = favoriteDao.getFavoritesList()
        val favoritesBySource = favorites.groupBy { it.sourceId }
        for ((sourceId, sourceFavorites) in favoritesBySource) {
            try {
                val source = MangaSource.fromId(sourceId)
                val homeData = mangaRepository.getHomeData(source).getOrDefault(
                    com.exapps.mangaworld.domain.model.HomeData()
                )
                for (favorite in sourceFavorites) {
                    val fetchedCount = homeData.latestChapters.filter {
                        it.mangaId == favorite.mangaId || it.mangaSlug == favorite.slug
                    }.size
                    if (fetchedCount > 0) {
                        prefs.edit().putInt("count_${favorite.mangaId}", fetchedCount).apply()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun showNewChaptersNotification(chapters: List<Pair<String, String>>) {
        val intent = AppLaunchIntents.latestUpdates(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_NEW_CHAPTERS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (chapters.size == 1) {
            "فصل جديد: ${chapters[0].first}"
        } else {
            "${chapters.size} فصول جديدة في مفضلتك"
        }

        val body = chapters.take(5).joinToString("\n") { (manga, info) ->
            "• $manga — $info"
        }

        val notification = NotificationCompat.Builder(context, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_NEW_CHAPTERS, notification)
    }

    companion object {
        private const val NOTIFICATION_ID_NEW_CHAPTERS = 7000
    }
}
