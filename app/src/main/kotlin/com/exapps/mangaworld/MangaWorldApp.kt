package com.exapps.mangaworld

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.disk.DiskCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.exapps.mangaworld.core.firebase.FirebaseStartupCoordinator
import com.exapps.mangaworld.core.firebase.FirebaseSyncWorker
import com.exapps.mangaworld.core.widget.AppShortcutManager
import com.exapps.mangaworld.core.widget.WidgetRefreshScheduler
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MangaWorldApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetRefreshScheduler: WidgetRefreshScheduler
    @Inject lateinit var appShortcutManager: AppShortcutManager
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var firebaseStartupCoordinator: FirebaseStartupCoordinator

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        val limitMb = runBlocking { settingsRepository.getAppSettings().first().imageCacheLimitMb }
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_image_cache"))
                    .maxSizeBytes(limitMb.coerceAtLeast(64).toLong() * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        widgetRefreshScheduler.schedule()
        scheduleFirebaseSync()
        applicationScope.launch {
            appShortcutManager.refreshDynamicShortcuts()
            firebaseStartupCoordinator.initialize()
        }
    }

    private fun scheduleFirebaseSync() {
        val constraints = Constraints.Builder().build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "firebase_sync_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FirebaseSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Progress channel — silent / low importance so it doesn't spam
            manager.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    "تنزيل الفصول",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "تقدم تنزيل الفصول" }
            )

            // Completion/failure channel — normal importance so user sees it
            manager.createNotificationChannel(
                NotificationChannel(
                    COMPLETE_CHANNEL_ID,
                    "إشعارات التنزيل",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "الفصول المكتملة والفاشلة" }
            )
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
        const val COMPLETE_CHANNEL_ID = "complete_channel"
    }
}
