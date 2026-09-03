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
import com.exapps.mangaworld.domain.model.NotificationDeliveryMode
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
                appContext.getString(com.exapps.mangaworld.R.string.suggestion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(com.exapps.mangaworld.R.string.suggestion_channel_desc)
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
            // Respect delivery mode — only INSTANT notifications fire immediately
            if (settings.notificationDeliveryMode != NotificationDeliveryMode.INSTANT) return@withContext Result.success()

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
            val existingIds = suggestionsManager.getSuggestions(60).map { it.mangaId }.toSet()

            // v8 (#5 fix): score ONLY titles the user has not been shown yet.
            // Previously the engine scored every cached manga and THEN dropped
            // the ones already suggested — the deterministic top-scores were
            // always filtered out, so after the first cycle no notification
            // could ever fire again.
            var recommendations = recommendationEngine.getSmartRecommendations(
                cachedMangas.filterNot { it.id in existingIds },
                limit = 10
            )
            if (recommendations.isEmpty()) {
                // Every cached title has been suggested once — rotate by
                // forgetting the history so the cycle can restart.
                recommendations = run {
                    suggestionsManager.clear()
                    recommendationEngine.getSmartRecommendations(cachedMangas, limit = 10)
                }
            }
            val newSuggestions = recommendations

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
            val extraText = if (newSuggestions.size > 3) ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_extra, newSuggestions.size - 3) else ""

            // "Read Now" action
            val readAction = NotificationCompat.Action(
                android.R.drawable.stat_notify_chat,
                ctx.getString(com.exapps.mangaworld.R.string.notif_action_read_now),
                pendingIntent
            )

            // "More" action — opens the in-app suggestions screen (Kotatsu parity).
            val moreIntent = AppLaunchIntents.suggestions(ctx)
            val morePendingIntent = PendingIntent.getActivity(
                ctx,
                SUGGESTION_NOTIFICATION_ID + 2,
                moreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val moreAction = NotificationCompat.Action(
                android.R.drawable.ic_menu_more,
                ctx.getString(com.exapps.mangaworld.R.string.more_title),
                morePendingIntent
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
                ctx.getString(com.exapps.mangaworld.R.string.notif_action_add_favorite),
                favPendingIntent
            )

            val notification = NotificationCompat.Builder(ctx, SUGGESTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$body$extraText"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setGroup("mw_suggestions")
                .addAction(readAction)
                .addAction(moreAction)
                .addAction(favAction)
                .build()

            notificationManager.notify(SUGGESTION_NOTIFICATION_ID, notification)

            // v8 (#11): log into the Notification Centre like every other channel.
            com.exapps.mangaworld.core.data.NotificationCenterStore.update(ctx) { arr ->
                val obj = org.json.JSONObject().apply {
                    put("id", "suggestion_${System.currentTimeMillis()}")
                    put("title", ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_center_title))
                    put("body", "$body$extraText")
                    put("type", "suggestion")
                    put("read", false)
                    put("timestamp", System.currentTimeMillis())
                }
                arr.put(obj)
                while (arr.length() > 100) { arr.remove(0) }
            }

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
