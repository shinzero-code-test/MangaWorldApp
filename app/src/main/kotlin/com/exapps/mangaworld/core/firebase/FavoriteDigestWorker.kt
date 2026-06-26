package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.model.NotificationDeliveryMode
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class FavoriteDigestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mangaRepository: MangaRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = settingsRepository.getAppSettings().first()
        if (!settings.enableNotifications || settings.notificationDeliveryMode != NotificationDeliveryMode.DAILY_DIGEST) {
            return Result.success()
        }
        val favorites = libraryRepository.getFavorites().first()
        if (favorites.isEmpty()) return Result.success()

        val favoriteIds = favorites.map { it.mangaId }.toSet()
        val updates = settings.enabledSources.flatMap { sourceId ->
            val source = com.exapps.mangaworld.domain.model.MangaSource.fromId(sourceId)
            mangaRepository.getHomeData(source).getOrDefault(com.exapps.mangaworld.domain.model.HomeData()).latestChapters
        }.filter { it.mangaId in favoriteIds }.distinctBy { it.chapterUrl }

        if (updates.isEmpty()) return Result.success()

        val title = "ملخص اليوم من MangaWorld"
        val body = updates.take(5).joinToString(" • ") { "${it.mangaTitle} - الفصل ${it.chapterNumber}" }
        val intent = AppLaunchIntents.latestUpdates(applicationContext)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            5002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID_DAILY_DIGEST, notification)
        return Result.success()
    }

    companion object {
        private const val NOTIFICATION_ID_DAILY_DIGEST = 5002
    }
}
