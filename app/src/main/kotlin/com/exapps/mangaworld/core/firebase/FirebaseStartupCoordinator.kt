package com.exapps.mangaworld.core.firebase

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStartupCoordinator @Inject constructor(
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager
) {
    suspend fun initialize() {
        sessionManager.ensureGuestSession()
        remoteConfigManager.refresh()
        runCatching { syncManager.pushLocalSnapshot() }
    }
}
