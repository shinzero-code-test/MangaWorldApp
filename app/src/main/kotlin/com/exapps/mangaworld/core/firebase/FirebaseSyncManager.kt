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
            AppTheme.values().firstOrNull { it.name == name }?.let { theme ->
                settingsRepository.updateTheme(theme)
            }
        }
        (profile.get("enabledSources") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let { sourceIds ->
            settingsRepository.setEnabledSources(sourceIds)
        }
        profile.getBoolean("useDynamicColors")?.let { settingsRepository.setDynamicColors(it) }
        profile.getBoolean("biometricLockEnabled")?.let { settingsRepository.setBiometricLock(it) }
        profile.getBoolean("secureReaderEnabled")?.let { settingsRepository.setSecureReader(it) }
        profile.getBoolean("autoCleanupReadDownloads")?.let { settingsRepository.setAutoCleanupReadDownloads(it) }
        profile.getLong("cleanupAfterHours")?.toInt()?.let { settingsRepository.setCleanupAfterHours(it) }
        profile.getLong("imageCacheLimitMb")?.toInt()?.let { settingsRepository.setImageCacheLimitMb(it) }
        (profile.get("contentBlacklist") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let { blacklist ->
            settingsRepository.setContentBlacklist(blacklist)
        }

        readerPrefs.getString("mode")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderMode.values().firstOrNull { it.name == name }?.let { mode ->
                settingsRepository.updateReaderMode(mode)
            }
        }
        readerPrefs.getDouble("brightness")?.toFloat()?.let { settingsRepository.updateBrightness(it) }
        readerPrefs.getBoolean("keepScreenOn")?.let { settingsRepository.updateKeepScreenOn(it) }
        readerPrefs.getBoolean("autoWebtoonDetection")?.let { settingsRepository.updateAutoWebtoon(it) }
        readerPrefs.getBoolean("incognitoMode")?.let { settingsRepository.updateIncognitoMode(it) }
        readerPrefs.getBoolean("smartPrefetchEnabled")?.let { settingsRepository.updateSmartPrefetch(it) }
        readerPrefs.getBoolean("hapticsEnabled")?.let { settingsRepository.updateReaderHaptics(it) }
        readerPrefs.getString("imageFilter")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderImageFilter.values().firstOrNull { it.name == name }?.let { filter ->
                settingsRepository.updateImageFilter(filter)
            }
        }
    }
}
