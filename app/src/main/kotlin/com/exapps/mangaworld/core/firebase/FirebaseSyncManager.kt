package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.domain.model.AppTheme
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
        val reader = settingsRepository.getReaderSettings().first()

        val userRef = firestore.collection("users").document(uid)
        userRef.set(
            mapOf(
                "updatedAt" to System.currentTimeMillis(),
                "enabledSources" to settings.enabledSources.toList(),
                "theme" to settings.theme.name,
                "useDynamicColors" to settings.useDynamicColors,
                "biometricLockEnabled" to settings.biometricLockEnabled,
                "secureReaderEnabled" to settings.secureReaderEnabled,
                "autoCleanupReadDownloads" to settings.autoCleanupReadDownloads,
                "cleanupAfterHours" to settings.cleanupAfterHours,
                "imageCacheLimitMb" to settings.imageCacheLimitMb,
                "contentBlacklist" to settings.contentBlacklist.toList()
            ),
            SetOptions.merge()
        ).await()

        userRef.collection("preferences").document("reader")
            .set(reader, SetOptions.merge())
            .await()

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

    suspend fun pullRemoteSnapshot() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val userRef = firestore.collection("users").document(uid)
        val profile = userRef.get().await()
        val favorites = userRef.collection("favorites").get().await().documents
        val history = userRef.collection("readingHistory").get().await().documents
        val readerPrefs = userRef.collection("preferences").document("reader").get().await()

        favorites.mapNotNull { it.toObject(FavoriteEntity::class.java) }.forEach { favoriteDao.insert(it) }
        history.mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }.forEach { historyDao.insertOrUpdate(it) }

        profile.getString("theme")?.let { name ->
            AppTheme.values().firstOrNull { it.name == name }?.let(settingsRepository::updateTheme)
        }
        (profile.get("enabledSources") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let(settingsRepository::setEnabledSources)
        profile.getBoolean("useDynamicColors")?.let(settingsRepository::setDynamicColors)
        profile.getBoolean("biometricLockEnabled")?.let(settingsRepository::setBiometricLock)
        profile.getBoolean("secureReaderEnabled")?.let(settingsRepository::setSecureReader)
        profile.getBoolean("autoCleanupReadDownloads")?.let(settingsRepository::setAutoCleanupReadDownloads)
        profile.getLong("cleanupAfterHours")?.toInt()?.let(settingsRepository::setCleanupAfterHours)
        profile.getLong("imageCacheLimitMb")?.toInt()?.let(settingsRepository::setImageCacheLimitMb)
        (profile.get("contentBlacklist") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let(settingsRepository::setContentBlacklist)

        readerPrefs.getString("mode")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderMode.values().firstOrNull { it.name == name }?.let(settingsRepository::updateReaderMode)
        }
        readerPrefs.getDouble("brightness")?.toFloat()?.let(settingsRepository::updateBrightness)
        readerPrefs.getBoolean("keepScreenOn")?.let(settingsRepository::updateKeepScreenOn)
        readerPrefs.getBoolean("autoWebtoonDetection")?.let(settingsRepository::updateAutoWebtoon)
        readerPrefs.getBoolean("incognitoMode")?.let(settingsRepository::updateIncognitoMode)
        readerPrefs.getBoolean("smartPrefetchEnabled")?.let(settingsRepository::updateSmartPrefetch)
        readerPrefs.getBoolean("hapticsEnabled")?.let(settingsRepository::updateReaderHaptics)
        readerPrefs.getString("imageFilter")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderImageFilter.values().firstOrNull { it.name == name }?.let(settingsRepository::updateImageFilter)
        }
    }
}
