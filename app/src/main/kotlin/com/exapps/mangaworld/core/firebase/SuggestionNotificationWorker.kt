package com.exapps.mangaworld.core.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.RecommendationEngine
import com.exapps.mangaworld.core.data.SuggestionsManager
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SuggestionWorker"
private const val SUGGESTION_CHANNEL_ID = "suggestions_channel"

/**
 * Periodic worker that generates new manga suggestions and shows a notification.
 * Runs every 12 hours to suggest manga the user might enjoy.
 *
 * Uses cached manga as candidates (same as SuggestionsViewModel) and
 * [RecommendationEngine] for personalized scoring.
 */
@HiltWorker
class SuggestionNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recommendationEngine: RecommendationEngine,
    private val suggestionsManager: SuggestionsManager,
    private val cacheDao: MangaCacheDao,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs by lazy {
        appContext.getSharedPreferences("suggestion_notification_prefs", Context.MODE_PRIVATE)
    }

    init {
        // Create low-importance channel for suggestions (separate from CLOUD_CHANNEL_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUGGESTION_CHANNEL_ID,
                "اقتراحات المانجا",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "اقتراحات مانجا قد تعجبك"
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val ctx = applicationContext
            val settings = settingsRepository.getAppSettings().first()
            if (!settings.enableNotifications) return@withContext Result.success()

            // Load cached manga as candidates
            val cachedMangas = cacheDao.getAll(200).mapNotNull { cache ->
                try {
                    MangaItem(
                        id = cache.mangaId,
                        slug = cache.slug,
                        title = cache.title,
                        coverUrl = cache.coverUrl,
                        source = MangaSource.fromId(cache.sourceId),
                        genres = try {
                            org.json.JSONArray(cache.genresJson).let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            }
                        } catch (_: Exception) { emptyList() },
                        status = try {
                            com.exapps.mangaworld.domain.model.MangaStatus.valueOf(cache.statusStr)
                        } catch (_: Exception) { com.exapps.mangaworld.domain.model.MangaStatus.UNKNOWN },
                        type = try {
                            com.exapps.mangaworld.domain.model.MangaType.valueOf(cache.typeStr)
                        } catch (_: Exception) { com.exapps.mangaworld.domain.model.MangaType.UNKNOWN },
                        rating = cache.rating,
                        latestChapter = cache.latestChapter,
                        totalChapters = cache.totalChapters,
                        url = cache.url
                    )
                } catch (_: Exception) { null }
            }

            if (cachedMangas.isEmpty()) return@withContext Result.success()

            // Get existing suggestions to avoid duplicate notifications
            val existingIds = suggestionsManager.getSuggestions(200).map { it.mangaId }.toSet()

            // Generate recommendations
            val recommendations = recommendationEngine.getSmartRecommendations(cachedMangas, limit = 10)
            val newSuggestions = recommendations.filter { it.id !in existingIds }

            if (newSuggestions.isEmpty()) return@withContext Result.success()

            // Show notification
            val intent = AppLaunchIntents.home(ctx)
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                SUGGESTION_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_title)
            val body = newSuggestions.take(3).joinToString("\n") { "• ${it.title}" }
            val extraText = if (newSuggestions.size > 3) "\nو ${newSuggestions.size - 3} أخرى" else ""

            // "Read Now" action
            val readAction = NotificationCompat.Action(
                android.R.drawable.stat_notify_chat,
                "اقرأ الآن",
                pendingIntent
            )

            // "Add to Favourite" action — adds the top suggestion
            val topSuggestion = newSuggestions.first()
            val favIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ADD_FAVORITE
                putExtra(NotificationActionReceiver.EXTRA_MANGA_ID, topSuggestion.id)
                putExtra(NotificationActionReceiver.EXTRA_TITLE, topSuggestion.title)
                putExtra(NotificationActionReceiver.EXTRA_SOURCE_ID, topSuggestion.source.id)
                putExtra(NotificationActionReceiver.EXTRA_SLUG, topSuggestion.slug)
                putExtra(NotificationActionReceiver.EXTRA_COVER_URL, topSuggestion.coverUrl)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, SUGGESTION_NOTIFICATION_ID)
            }
            val favPendingIntent = PendingIntent.getBroadcast(
                ctx,
                SUGGESTION_NOTIFICATION_ID + 1,
                favIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val favAction = NotificationCompat.Action(
                android.R.drawable.btn_star,
                "إضافة للمفضلة",
                favPendingIntent
            )

            val notification = NotificationCompat.Builder(ctx, SUGGESTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$body$extraText"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .addAction(readAction)
                .addAction(favAction)
                .build()

            notificationManager.notify(SUGGESTION_NOTIFICATION_ID, notification)

            // Update persistent suggestions
            val mangaSuggestions = newSuggestions.map { manga ->
                com.exapps.mangaworld.core.data.MangaSuggestion(
                    mangaId = manga.id,
                    title = manga.title,
                    coverUrl = manga.coverUrl,
                    sourceId = manga.source.id,
                    relevance = 0.5f
                )
            }
            suggestionsManager.addSuggestions(mangaSuggestions)

            prefs.edit().putLong("last_suggestion_notification", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Suggestion worker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val SUGGESTION_NOTIFICATION_ID = 8000
    }
}
