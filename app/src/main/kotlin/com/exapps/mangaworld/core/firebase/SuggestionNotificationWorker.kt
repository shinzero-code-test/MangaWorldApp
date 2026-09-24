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
    private val settingsRepository: SettingsRepository,
    private val bitmapLoader: NotificationBitmapLoader
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
                        source = com.exapps.mangaworld.core.source.plugins.SourceId(cache.sourceId),
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
                        url = cache.url,
                        description = cache.description
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

            // One rich notification PER manga — never a multi-title digest.
            // Each carries the manga title, a description snippet, the score +
            // genres meta line, and the cover (BigPicture, memory-bounded via
            // NotificationBitmapLoader). Capped per cycle to avoid spam.
            val morePendingIntent = PendingIntent.getActivity(
                ctx,
                SUGGESTION_NOTIFICATION_ID_BASE + MORE_REQUEST_OFFSET,
                AppLaunchIntents.suggestions(ctx),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val moreAction = NotificationCompat.Action(
                android.R.drawable.ic_menu_more,
                ctx.getString(com.exapps.mangaworld.R.string.more_title),
                morePendingIntent
            )
            newSuggestions.take(MAX_NOTIFICATIONS_PER_CYCLE).forEachIndexed { index, manga ->
                notifySuggestion(ctx, manga, index, moreAction)
            }

            // Update persistent suggestions
            val mangaSuggestions = newSuggestions.map { manga ->
                com.exapps.mangaworld.core.data.MangaSuggestion(
                    mangaId = manga.id,
                    title = manga.title,
                    coverUrl = manga.coverUrl,
                    sourceId = manga.source.value,
                    relevance = 0.5f
                )
            }
            suggestionsManager.addSuggestions(mangaSuggestions)

            prefs.edit().putLong("last_suggestion_notification", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Suggestion worker failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun notifySuggestion(
        ctx: Context,
        manga: MangaItem,
        index: Int,
        moreAction: NotificationCompat.Action
    ) {
        val notificationId = SUGGESTION_NOTIFICATION_ID_BASE + index
        val genreSeparator = ctx.getString(com.exapps.mangaworld.R.string.suggestion_genre_separator)
        val ratingText = manga.rating?.takeIf { it > 0f }?.let { "%.1f".format(it) }
        val genreText = manga.genres.take(3).joinToString(genreSeparator).takeIf { it.isNotBlank() }
        // Score + genre meta line (all user-visible text from resources).
        val meta = when {
            ratingText != null && genreText != null ->
                ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_meta, ratingText, genreText)
            ratingText != null ->
                ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_rating_only, ratingText)
            else -> genreText.orEmpty()
        }
        val descSnippet = manga.description.trim().take(DESCRIPTION_SNIPPET_LENGTH)
            .ifBlank { ctx.getString(com.exapps.mangaworld.R.string.suggestion_notif_no_desc) }

        // Deep link straight to this manga's detail screen.
        val detailIntent = AppLaunchIntents.detail(ctx, manga.source.value, manga.slug)
        val detailPendingIntent = PendingIntent.getActivity(
            ctx,
            notificationId,
            detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readAction = NotificationCompat.Action(
            android.R.drawable.stat_notify_chat,
            ctx.getString(com.exapps.mangaworld.R.string.notif_action_read_now),
            detailPendingIntent
        )

        // "Add to Favourite" action — scoped to THIS manga, dismisses its own notification.
        val favIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ADD_FAVORITE
            putExtra(NotificationActionReceiver.EXTRA_MANGA_ID, manga.id)
            putExtra(NotificationActionReceiver.EXTRA_TITLE, manga.title)
            putExtra(NotificationActionReceiver.EXTRA_SOURCE_ID, manga.source.value)
            putExtra(NotificationActionReceiver.EXTRA_SLUG, manga.slug)
            putExtra(NotificationActionReceiver.EXTRA_COVER_URL, manga.coverUrl)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val favAction = NotificationCompat.Action(
            android.R.drawable.btn_star,
            ctx.getString(com.exapps.mangaworld.R.string.notif_action_add_favorite),
            PendingIntent.getBroadcast(
                ctx,
                SUGGESTION_NOTIFICATION_ID_BASE + FAVORITE_REQUEST_OFFSET + index,
                favIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val builder = NotificationCompat.Builder(ctx, SUGGESTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(manga.title)
            .setContentText(descSnippet)
            .setContentIntent(detailPendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .addAction(readAction)
            .addAction(favAction)
            .addAction(moreAction)
        val cover = bitmapLoader.load(manga.coverUrl)
        if (cover != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(cover)
                    .setBigContentTitle(manga.title)
                    .setSummaryText(meta.ifBlank { descSnippet })
            )
        } else {
            if (meta.isNotBlank()) builder.setSubText(meta)
            val bigText = if (meta.isNotBlank()) "$meta\n$descSnippet" else descSnippet
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        notificationManager.notify(notificationId, builder.build())

        // v8 (#11): log into the Notification Centre like every other channel —
        // one entry per manga so history mirrors what was shown.
        com.exapps.mangaworld.core.data.NotificationCenterStore.update(ctx) { arr ->
            val obj = org.json.JSONObject().apply {
                put("id", "suggestion_${manga.id}_${System.currentTimeMillis()}")
                put("title", manga.title)
                put("body", if (meta.isNotBlank()) "$meta\n$descSnippet" else descSnippet)
                put("type", "suggestion")
                put("mangaId", manga.id)
                put("read", false)
                put("timestamp", System.currentTimeMillis())
            }
            arr.put(obj)
            while (arr.length() > 100) { arr.remove(0) }
        }
    }

    companion object {
        // Firebase lane [90000..90999] — disjoint from per-manga [70000..89999]
        // and the other firebase-lane IDs (90001 chapter digest, 90002 reminder).
        // One ID per showcased manga so notifications never overwrite each other.
        private const val SUGGESTION_NOTIFICATION_ID_BASE = 90010
        private const val FAVORITE_REQUEST_OFFSET = 100
        private const val MORE_REQUEST_OFFSET = 200
        /** Per-cycle cap: a suggestion burst must read as highlights, not spam. */
        private const val MAX_NOTIFICATIONS_PER_CYCLE = 3
        private const val DESCRIPTION_SNIPPET_LENGTH = 220
    }
}
