package com.exapps.mangaworld

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.exapps.mangaworld.core.widget.AppShortcutManager
import com.exapps.mangaworld.core.widget.WidgetRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MangaWorldApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetRefreshScheduler: WidgetRefreshScheduler
    @Inject lateinit var appShortcutManager: AppShortcutManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        widgetRefreshScheduler.schedule()
        applicationScope.launch {
            appShortcutManager.refreshDynamicShortcuts()
        }
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
