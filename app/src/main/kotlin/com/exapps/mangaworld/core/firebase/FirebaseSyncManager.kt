package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager
) {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun pushLocalSnapshot() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val favorites = favoriteDao.getFavoritesList()
        val history = historyDao.getRecent(100)
        val settings = settingsRepository.getAppSettings().first()

        val userRef = firestore.collection("users").document(uid)
        userRef.set(
            mapOf(
                "updatedAt" to System.currentTimeMillis(),
                "enabledSources" to settings.enabledSources.toList(),
                "theme" to settings.theme.name
            ),
            SetOptions.merge()
        ).await()

        favorites.forEach { favorite ->
            userRef.collection("favorites").document(favorite.mangaId)
                .set(favorite, SetOptions.merge())
                .await()
        }

        history.forEach { item ->
            userRef.collection("readingHistory").document(item.mangaId)
                .set(item, SetOptions.merge())
                .await()
        }
    }
}
