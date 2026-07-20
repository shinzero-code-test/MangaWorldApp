package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseUserInsightsCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val readingStatsStore: ReadingStatsStore,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val sessionManager: FirebaseSessionManager,
    private val analyticsManager: FirebaseAnalyticsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            sessionManager.authState.collectLatest { user ->
                analyticsManager.setUserId(user?.uid)
            }
        }

        scope.launch {
            settingsRepository.getAppSettings().collectLatest { settings ->
                analyticsManager.setUserProperty("preferred_theme", settings.theme.name.lowercase(Locale.US))
                analyticsManager.setUserProperty("notifications_enabled", if (settings.enableNotifications) "enabled" else "disabled")
                analyticsManager.setUserProperty("notification_mode", settings.notificationDeliveryMode.name.lowercase(Locale.US))
            }
        }

        scope.launch {
            settingsRepository.getReaderSettings().collectLatest { settings ->
                analyticsManager.setUserProperty("reading_mode", settings.mode.name.lowercase(Locale.US))
                analyticsManager.setUserProperty("reading_filter", settings.imageFilter.name.lowercase(Locale.US))
            }
        }

        scope.launch {
            remoteConfigManager.homeLayoutVariant.collectLatest { variant ->
                analyticsManager.setUserProperty("home_layout_variant", variant.ifBlank { "default" })
            }
        }

        scope.launch {
            readingStatsStore.totalReadingTimeMs.collectLatest { totalMs ->
                analyticsManager.setUserProperty("reader_engagement_tier", engagementTier(totalMs))
            }
        }
    }

    private fun engagementTier(totalMs: Long): String {
        // Thresholds from Remote Config (defaults: 15min, 1hr, 10hr)
        val warmingMs = remoteConfigManager.engagementWarmingMs.value
        val activeMs = remoteConfigManager.engagementActiveMs.value
        val avidMs = remoteConfigManager.engagementAvidMs.value
        return when {
            totalMs >= avidMs -> "avid"
            totalMs >= activeMs -> "active"
            totalMs >= warmingMs -> "warming"
            else -> "new"
        }
    }
}
