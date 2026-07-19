import com.exapps.mangaworld.R
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
import coil.memory.MemoryCache
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.exapps.mangaworld.core.firebase.FirebaseStartupCoordinator
import com.exapps.mangaworld.core.firebase.FirebaseSyncWorker
import com.exapps.mangaworld.core.firebase.FavoriteDigestWorker
import com.exapps.mangaworld.core.firebase.SuggestionNotificationWorker
import com.exapps.mangaworld.core.widget.AppShortcutManager
import com.exapps.mangaworld.core.widget.WidgetRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    @Inject lateinit var firebaseStartupCoordinator: FirebaseStartupCoordinator

    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_image_cache"))
                    .maxSizeBytes(DEFAULT_IMAGE_CACHE_MB.toLong() * 1024L * 1024L)
                    .build()
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.30) // Use 30% of app memory for image cache
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        com.facebook.FacebookSdk.sdkInitialize(this)
        initializeAppCheck()
        createNotificationChannels()
        widgetRefreshScheduler.schedule()
        scheduleFirebaseSync()
        scheduleAutoDownload()
        applicationScope.launch {
            appShortcutManager.refreshDynamicShortcuts()
            firebaseStartupCoordinator.initialize()
        }
    }

    private fun initializeAppCheck() {
        try {
            val firebaseAppCheck = FirebaseAppCheck.getInstance()
            val providerFactory = if (BuildConfig.DEBUG) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                try {
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                } catch (_: Exception) {
                    // Play Integrity unavailable (no Play Services) — fall back to debug provider
                    DebugAppCheckProviderFactory.getInstance()
                }
            }
            firebaseAppCheck.installAppCheckProviderFactory(providerFactory)
            firebaseAppCheck.setTokenAutoRefreshEnabled(true)
        } catch (e: Exception) {
            android.util.Log.e("MangaWorldApp", "App Check initialization failed: ${e.message}")
        }
    }

    private fun scheduleFirebaseSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "firebase_sync_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FirebaseSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "favorite_digest_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FavoriteDigestWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "suggestion_notification_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SuggestionNotificationWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
        )
    }

    private fun scheduleAutoDownload() {
        com.exapps.mangaworld.core.data.download.AutoDownloadWorker.schedule(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Progress channel — silent / low importance so it doesn't spam
            manager.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    getString(R.string.channel_downloads),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.channel_downloads_desc) }
            )

            // Completion/failure channel — normal importance so user sees it
            manager.createNotificationChannel(
                NotificationChannel(
                    COMPLETE_CHANNEL_ID,
                    getString(R.string.channel_downloads_notif),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = getString(R.string.channel_downloads_notif_desc) }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CLOUD_CHANNEL_ID,
                    getString(R.string.channel_cloud_sources),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = getString(R.string.channel_cloud_sources_desc) }
            )
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
        const val COMPLETE_CHANNEL_ID = "complete_channel"
        const val CLOUD_CHANNEL_ID = "cloud_channel"
        private const val DEFAULT_IMAGE_CACHE_MB = 250
    }
}
