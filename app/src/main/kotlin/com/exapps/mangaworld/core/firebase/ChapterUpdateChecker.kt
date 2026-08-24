package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.util.Log
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.exapps.mangaworld.R
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.domain.model.NotificationDeliveryMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local chapter update detector — checks sources for new chapters
 * on favorited manga and shows notifications without FCM.
 *
 * Detection strategy: For each favorited manga, we look at the home page's
 * `latestChapters` list and find the HIGHEST chapter number for that manga.
 * We compare it against the previously stored max chapter number. If the
 * current max is higher, a new chapter was published.
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

    private val checkMutex = Mutex()

    private val prefs by lazy {
        context.getSharedPreferences("chapter_update_prefs", Context.MODE_PRIVATE)
    }

    private data class NewChapterInfo(
        val title: String,
        val info: String,
        val mangaId: String,
        val sourceId: String,
        val slug: String,
        val coverUrl: String
    )

    suspend fun checkForUpdates(forceCheck: Boolean = false) = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val settings = settingsRepository.getAppSettings().first()
            if (!settings.enableNotifications) return@withContext
            // Respect delivery mode — only INSTANT notifications fire immediately
            if (settings.notificationDeliveryMode != NotificationDeliveryMode.INSTANT) return@withContext

        // Throttle: check at most once per 2 hours (bypassed by WorkManager via forceCheck)
        val lastCheck = prefs.getLong("last_update_check", 0L)
        val now = System.currentTimeMillis()
        if (!forceCheck && now - lastCheck < 2 * 60 * 60 * 1000L) return@withContext

        val favorites = favoriteDao.getFavoritesList()
        if (favorites.isEmpty()) return@withContext

        val favoritesBySource = favorites.groupBy { it.sourceId }
        val newChapters = mutableListOf<NewChapterInfo>()

        for ((sourceId, sourceFavorites) in favoritesBySource) {
            if (sourceId !in settings.enabledSources) continue

            try {
                val source = MangaSource.fromId(sourceId)
                val homeData = mangaRepository.getHomeData(source).getOrDefault(
                    com.exapps.mangaworld.domain.model.HomeData()
                )

                for (favorite in sourceFavorites) {
                    val mangaChapters = homeData.latestChapters.filter {
                        it.mangaId == favorite.mangaId || it.mangaSlug == favorite.slug
                    }
                    if (mangaChapters.isEmpty()) continue

                    val currentMaxChapter = mangaChapters.maxOf { it.chapterNumber.toDouble() }
                    val lastKnownMax = prefs.getFloat("max_chapter_${favorite.mangaId}", -1f).toDouble()

                    if (lastKnownMax < 0.0) {
                        prefs.edit().putFloat("max_chapter_${favorite.mangaId}", currentMaxChapter.toFloat()).apply()
                    } else if (currentMaxChapter > lastKnownMax) {
                        val diff = mangaChapters.count { it.chapterNumber.toDouble() > lastKnownMax }
                            .coerceAtLeast(1)
                        newChapters.add(
                            NewChapterInfo(
                                title = favorite.title,
                                info = context.getString(
                                    if (diff > 1) R.string.notif_new_chapter_info_plural
                                    else R.string.notif_new_chapter_info_single,
                                    diff
                                ),
                                mangaId = favorite.mangaId,
                                sourceId = favorite.sourceId,
                                slug = favorite.slug,
                                coverUrl = favorite.coverUrl
                            )
                        )
                        prefs.edit().putFloat("max_chapter_${favorite.mangaId}", currentMaxChapter.toFloat()).apply()
                    }
                }
            } catch (e: Exception) {
                Log.w("ChapterUpdateChecker", "Failed to check updates for source $sourceId: ${e.message}")
            }
        }

        if (newChapters.isNotEmpty()) {
            showNewChaptersNotification(newChapters)
        }

        // Throttle timestamp is written only after a completed sweep — a mid-loop
        // failure must not consume the 2h window (L-review).
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).commit()
        } // end checkMutex.withLock
    }

    // snapshotCurrentCounts() deleted (L-review): zero callers, mutated
    // max_chapter_* prefs outside the check mutex.

    private suspend fun showNewChaptersNotification(chapters: List<NewChapterInfo>) {
        // Content intent — open latest updates
        val contentIntent = AppLaunchIntents.latestUpdates(context)
        val pendingContentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_NEW_CHAPTERS,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (chapters.size == 1) {
            context.getString(R.string.notif_new_chapter_title_single, chapters[0].title)
        } else {
            context.getString(R.string.notif_new_chapters_title_multi, chapters.size)
        }

        val body = chapters.take(5).joinToString("\n") { "• ${it.title} — ${it.info}" }

        // "Read Now" action
        val readAction = NotificationCompat.Action(
            android.R.drawable.stat_notify_chat,
            context.getString(R.string.notif_action_read_now),
            pendingContentIntent
        )

        // "Add to Favourite" action — only for single-manga notifications
        val favAction = if (chapters.size == 1) {
            val ch = chapters[0]
            val favIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ADD_FAVORITE
                putExtra(NotificationActionReceiver.EXTRA_MANGA_ID, ch.mangaId)
                putExtra(NotificationActionReceiver.EXTRA_TITLE, ch.title)
                putExtra(NotificationActionReceiver.EXTRA_SOURCE_ID, ch.sourceId)
                putExtra(NotificationActionReceiver.EXTRA_SLUG, ch.slug)
                putExtra(NotificationActionReceiver.EXTRA_COVER_URL, ch.coverUrl)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID_NEW_CHAPTERS)
            }
            val favPendingIntent = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID_NEW_CHAPTERS + 1,
                favIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(
                android.R.drawable.btn_star,
                context.getString(R.string.notif_action_add_favorite),
                favPendingIntent
            )
        } else null

        val builder = NotificationCompat.Builder(context, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingContentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY_CHAPTER_UPDATES)
            .setGroupSummary(false)
            .addAction(readAction)

        favAction?.let { builder.addAction(it) }

        notificationManager.notify(NOTIFICATION_ID_NEW_CHAPTERS, builder.build())

        // Persist to notification center
        saveToNotificationCenter(chapters)
    }

    private suspend fun saveToNotificationCenter(chapters: List<NewChapterInfo>) {
        com.exapps.mangaworld.core.data.NotificationCenterStore.update(context) { arr ->
            for (ch in chapters) {
                val obj = org.json.JSONObject().apply {
                    put("id", "chapter_${ch.mangaId}_${System.currentTimeMillis()}")
                    put("title", context.getString(R.string.notif_new_chapter_title_single, ch.title))
                    put("body", ch.info)
                    put("type", "chapter_update")
                    put("mangaId", ch.mangaId)
                    put("read", false)
                    put("timestamp", System.currentTimeMillis())
                }
                arr.put(obj)
            }

            // Keep only last 100 local notifications
            while (arr.length() > 100) { arr.remove(0) }
        }
    }

    companion object {
        private const val NOTIFICATION_ID_NEW_CHAPTERS = 7000
        private const val GROUP_KEY_CHAPTER_UPDATES = "chapter_updates"
    }
}
