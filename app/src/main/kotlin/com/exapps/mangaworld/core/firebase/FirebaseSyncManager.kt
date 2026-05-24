package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.model.CloudRestorePreview
import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readerAnnotationDao: ReaderAnnotationDao,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager
) {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun pushLocalSnapshot() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val favorites = favoriteDao.getFavoritesList()
        val history = historyDao.getAll()
        val annotations = readerAnnotationDao.getAll()
        val settings = settingsRepository.getAppSettings().first()
        val reader = settingsRepository.getReaderSettings().first()

        val userRef = firestore.collection("users").document(uid)
        val writes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Any>>()
        writes += userRef to mapOf(
            "updatedAt" to System.currentTimeMillis(),
            "enabledSources" to settings.enabledSources.toList(),
            "theme" to settings.theme.name,
            "useDynamicColors" to settings.useDynamicColors,
            "biometricLockEnabled" to settings.biometricLockEnabled,
            "secureReaderEnabled" to settings.secureReaderEnabled,
            "notificationDeliveryMode" to settings.notificationDeliveryMode.name,
            "autoCleanupReadDownloads" to settings.autoCleanupReadDownloads,
            "cleanupAfterHours" to settings.cleanupAfterHours,
            "imageCacheLimitMb" to settings.imageCacheLimitMb,
            "contentBlacklist" to settings.contentBlacklist.toList(),
            "spoilerCollapseDefault" to settings.spoilerCollapseDefault,
            "mutedUserIds" to settings.mutedUserIds.toList()
        )
        writes += userRef.collection("preferences").document("reader") to reader
        favorites.forEach { favorite -> writes += userRef.collection("favorites").document(favorite.mangaId) to favorite }
        history.forEach { item -> writes += userRef.collection("readingHistory").document(item.mangaId) to item }
        annotations.forEach { annotation -> writes += userRef.collection("readerAnnotations").document(annotationDocId(annotation)) to annotation }
        commitChunked(writes)
    }

    suspend fun pullRemoteSnapshot() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val userRef = firestore.collection("users").document(uid)
        val profile = userRef.get().await()
        val favorites = userRef.collection("favorites").get().await().documents
        val history = userRef.collection("readingHistory").get().await().documents
        val annotations = userRef.collection("readerAnnotations").get().await().documents
        val readerPrefs = userRef.collection("preferences").document("reader").get().await()

        favorites.mapNotNull { it.toObject(FavoriteEntity::class.java) }.forEach { favoriteDao.insert(it) }
        history.mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }.forEach { historyDao.insertOrUpdate(it) }
        annotations.mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }.forEach { readerAnnotationDao.upsert(it) }

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
        profile.getString("notificationDeliveryMode")?.let { name ->
            com.exapps.mangaworld.domain.model.NotificationDeliveryMode.values().firstOrNull { it.name == name }?.let { mode ->
                settingsRepository.setNotificationDeliveryMode(mode)
            }
        }
        profile.getBoolean("autoCleanupReadDownloads")?.let { settingsRepository.setAutoCleanupReadDownloads(it) }
        profile.getLong("cleanupAfterHours")?.toInt()?.let { settingsRepository.setCleanupAfterHours(it) }
        profile.getLong("imageCacheLimitMb")?.toInt()?.let { settingsRepository.setImageCacheLimitMb(it) }
        (profile.get("contentBlacklist") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let { blacklist ->
            settingsRepository.setContentBlacklist(blacklist)
        }
        profile.getBoolean("spoilerCollapseDefault")?.let { settingsRepository.setSpoilerCollapseDefault(it) }
        (profile.get("mutedUserIds") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let { muted ->
            settingsRepository.setMutedUserIds(muted)
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
        readerPrefs.getBoolean("autoOpenNextChapter")?.let { settingsRepository.updateAutoOpenNextChapter(it) }
        readerPrefs.getBoolean("showLiveReadersOverlay")?.let { settingsRepository.updateShowLiveReadersOverlay(it) }
        readerPrefs.getBoolean("showReactionOverlay")?.let { settingsRepository.updateShowReactionOverlay(it) }
        readerPrefs.getString("imageFilter")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderImageFilter.values().firstOrNull { it.name == name }?.let { filter ->
                settingsRepository.updateImageFilter(filter)
            }
        }
    }

    suspend fun previewRemoteSnapshot(): CloudRestorePreview {
        val uid = sessionManager.ensureGuestSession() ?: error("No user")
        val userRef = firestore.collection("users").document(uid)
        val profile = userRef.get().await()
        val remoteFavorites = userRef.collection("favorites").get().await().documents.mapNotNull { it.toObject(FavoriteEntity::class.java) }
        val remoteHistory = userRef.collection("readingHistory").get().await().documents.mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }
        val remoteAnnotations = userRef.collection("readerAnnotations").get().await().documents.mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }

        val localFavorites = favoriteDao.getFavoritesList()
        val localHistory = historyDao.getAll()
        val localAnnotations = readerAnnotationDao.getAll()
        val localTheme = settingsRepository.getAppSettings().first().theme
        val remoteTheme = profile.getString("theme")?.let { name -> AppTheme.values().firstOrNull { it.name == name } }

        val localLatest = localHistory.maxOfOrNull { it.lastReadAt } ?: 0L
        val remoteLatest = remoteHistory.maxOfOrNull { it.lastReadAt } ?: 0L
        val localLatestAnnotation = localAnnotations.maxOfOrNull { it.updatedAt } ?: 0L
        val remoteLatestAnnotation = remoteAnnotations.maxOfOrNull { it.updatedAt } ?: 0L
        val strategy = suggestCloudRestoreStrategy(
            localFavorites = localFavorites.size,
            remoteFavorites = remoteFavorites.size,
            localLatestHistoryAt = localLatest,
            remoteLatestHistoryAt = remoteLatest,
            localLatestAnnotationAt = localLatestAnnotation,
            remoteLatestAnnotationAt = remoteLatestAnnotation
        )
        return CloudRestorePreview(
            localFavorites = localFavorites.size,
            remoteFavorites = remoteFavorites.size,
            localHistory = localHistory.size,
            remoteHistory = remoteHistory.size,
            localAnnotations = localAnnotations.size,
            remoteAnnotations = remoteAnnotations.size,
            localLatestHistoryAt = localLatest,
            remoteLatestHistoryAt = remoteLatest,
            localLatestAnnotationAt = localLatestAnnotation,
            remoteLatestAnnotationAt = remoteLatestAnnotation,
            remoteTheme = remoteTheme,
            localTheme = localTheme,
            suggestedStrategy = strategy
        )
    }

    suspend fun applyRemoteRestore(strategy: CloudRestoreStrategy) {
        when (strategy) {
            CloudRestoreStrategy.KEEP_LOCAL -> pushLocalSnapshot()
            CloudRestoreStrategy.REMOTE_OVERWRITE -> pullRemoteSnapshot()
            CloudRestoreStrategy.MERGE -> mergeRemoteSnapshot()
        }
    }

    private suspend fun mergeRemoteSnapshot() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val userRef = firestore.collection("users").document(uid)
        val remoteFavorites = userRef.collection("favorites").get().await().documents.mapNotNull { it.toObject(FavoriteEntity::class.java) }
        val remoteHistory = userRef.collection("readingHistory").get().await().documents.mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }
        val remoteAnnotations = userRef.collection("readerAnnotations").get().await().documents.mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }
        val localFavorites = favoriteDao.getFavoritesList().associateBy { it.mangaId }
        val localHistory = historyDao.getAll().associateBy { it.mangaId }
        val localAnnotations = readerAnnotationDao.getAll().associateBy { annotationDocId(it) }

        (localFavorites + remoteFavorites.associateBy { it.mangaId }).values.forEach { favoriteDao.insert(it) }
        (localHistory + remoteHistory.associateBy { it.mangaId }).values
            .groupBy { it.mangaId }
            .values
            .map { list -> list.maxByOrNull { it.lastReadAt } }
            .filterNotNull()
            .forEach { historyDao.insertOrUpdate(it) }
        (localAnnotations + remoteAnnotations.associateBy { annotationDocId(it) }).values
            .groupBy { annotationDocId(it) }
            .values
            .mapNotNull { items -> items.maxByOrNull { it.updatedAt } }
            .forEach { readerAnnotationDao.upsert(it) }

        val profile = userRef.get().await()
        profile.getString("theme")?.let { name ->
            AppTheme.values().firstOrNull { it.name == name }?.let { theme -> settingsRepository.updateTheme(theme) }
        }
    }

    private fun annotationDocId(entity: ReaderAnnotationEntity): String =
        listOf(entity.mangaId, entity.chapterUrl.hashCode().toString(), entity.pageIndex.toString()).joinToString("_")

    private suspend fun commitChunked(writes: List<Pair<com.google.firebase.firestore.DocumentReference, Any>>) {
        writes.chunked(400).forEach { chunk ->
            val batch: WriteBatch = firestore.batch()
            chunk.forEach { (ref, value) -> batch.set(ref, value, SetOptions.merge()) }
            batch.commit().await()
        }
    }
}
