package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStartupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager,
    private val favoriteDao: FavoriteDao,
    private val topicManager: FirebaseTopicManager,
    private val messagingRegistrar: FirebaseMessagingRegistrar,
    private val userInsightsCoordinator: FirebaseUserInsightsCoordinator,
    private val notificationPolicyManager: NotificationPolicyManager
) {
    private val prefs by lazy {
        context.getSharedPreferences("firebase_startup_prefs", Context.MODE_PRIVATE)
    }

    suspend fun initialize() {
        sessionManager.ensureGuestSession()
        userInsightsCoordinator.start()
        remoteConfigManager.refresh()
        runCatching { messagingRegistrar.syncCurrentToken() }

        // Throttle full sync to once per hour to avoid redundant Firestore writes on every app launch
        val lastSync = prefs.getLong("last_push_sync", 0L)
        val now = System.currentTimeMillis()
        if (now - lastSync > 3_600_000L) { // 1 hour
            runCatching { syncManager.pushLocalSnapshot() }
            prefs.edit().putLong("last_push_sync", now).apply()
        }

        runCatching {
            favoriteDao.getFavoritesList().forEach { topicManager.subscribeToManga(it.mangaId) }
        }
        runCatching { notificationPolicyManager.checkAndSendReminders() }
    }
}
