package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStartupCoordinator @Inject constructor(
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager,
    private val favoriteDao: FavoriteDao,
    private val topicManager: FirebaseTopicManager,
    private val messagingRegistrar: FirebaseMessagingRegistrar,
    private val userInsightsCoordinator: FirebaseUserInsightsCoordinator
) {
    suspend fun initialize() {
        sessionManager.ensureGuestSession()
        userInsightsCoordinator.start()
        remoteConfigManager.refresh()
        runCatching { messagingRegistrar.syncCurrentToken() }
        runCatching { syncManager.pushLocalSnapshot() }
        runCatching {
            favoriteDao.getFavoritesList().forEach { topicManager.subscribeToManga(it.mangaId) }
        }
    }
}
