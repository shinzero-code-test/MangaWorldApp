package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.SyncTombstone
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readerAnnotationDao: ReaderAnnotationDao,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager,
    private val prefs: AppPreferences,
    private val firebaseTelemetry: FirebaseTelemetry,
    private val achievementManager: com.exapps.mangaworld.core.data.AchievementManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val syncMutex = Mutex()

    suspend fun pushLocalSnapshot() = syncMutex.withLock {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withLock
        val favorites = favoriteDao.getFavoritesList()
        val history = historyDao.getAll()
        val annotations = readerAnnotationDao.getAll()
        val tombstones = prefs.getSyncTombstones()
        val settings = settingsRepository.getAppSettings().first()
        val reader = settingsRepository.getReaderSettings().first()

        firebaseTelemetry.traceDatabaseSync(
            operation = "push",
            metrics = mapOf(
                "favorites" to favorites.size.toLong(),
                "history" to history.size.toLong(),
                "annotations" to annotations.size.toLong()
            )
        ) {
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
                "contentBlacklist" to settings.contentBlacklist.take(200).toList(),
                "spoilerCollapseDefault" to settings.spoilerCollapseDefault,
                "mutedUserIds" to settings.mutedUserIds.take(100).toList()
            )
            writes += userRef.collection("preferences").document("reader") to mapOf(
                "mode" to reader.mode.name,
                "brightness" to reader.brightness.toDouble(),
                "pageSpacing" to reader.pageSpacing,
                "keepScreenOn" to reader.keepScreenOn,
                "showPageNumber" to reader.showPageNumber,
                "autoWebtoonDetection" to reader.autoWebtoonDetection,
                "incognitoMode" to reader.incognitoMode,
                "smartPrefetchEnabled" to reader.smartPrefetchEnabled,
                "hapticsEnabled" to reader.hapticsEnabled,
                "imageFilter" to reader.imageFilter.name,
                "autoOpenNextChapter" to reader.autoOpenNextChapter,
                "showLiveReadersOverlay" to reader.showLiveReadersOverlay,
                "showReactionOverlay" to reader.showReactionOverlay,
                "dualPageLandscape" to reader.dualPageLandscape,
                "webtoonAutoStitch" to reader.webtoonAutoStitch,
                "volumeButtonPageTurn" to reader.volumeButtonPageTurn,
                "doubleTapZoom" to reader.doubleTapZoom
            )
            favorites.forEach { favorite -> writes += userRef.collection("favorites").document(favorite.mangaId) to favorite }
            history.forEach { item -> writes += userRef.collection("readingHistory").document(item.mangaId) to item }
            annotations.forEach { annotation -> writes += userRef.collection("readerAnnotations").document(annotationDocId(annotation)) to annotation }
            tombstones.forEach { tombstone ->
                writes += userRef.collection("syncTombstones").document(tombstoneDocumentId(tombstone)) to tombstone
            }

            // Sync achievements and goals
            runCatching {
                val achievementsData = achievementManager.syncToFirestoreMap()
                if (achievementsData.isNotEmpty()) {
                    writes += userRef.collection("preferences").document("achievements") to achievementsData
                }
            }

            commitChunked(writes)
        }
    }

    /**
     * Fetch all documents from a Firestore collection using cursor-based pagination.
     * Caps at [maxDocs] total to prevent unbounded reads for power users.
     */
    private suspend fun fetchAllCollection(
        ref: com.google.firebase.firestore.CollectionReference,
        maxDocs: Int = 10_000
    ): List<com.google.firebase.firestore.DocumentSnapshot> {
        val result = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
        var lastDocId: String? = null
        while (result.size < maxDocs) {
            val pageSize = minOf(1000, maxDocs - result.size)
            val query = if (lastDocId != null) {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId())
                    .startAfter(lastDocId).limit(pageSize)
            } else {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId()).limit(pageSize)
            }
            val snap = query.get().await()
            if (snap.isEmpty) break
            result.addAll(snap.documents)
            lastDocId = snap.documents.lastOrNull()?.id ?: break
            if (snap.size() < pageSize) break
        }
        return result
    }

    suspend fun pullRemoteSnapshot() = syncMutex.withLock {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withLock
        firebaseTelemetry.traceDatabaseSync(operation = "pull") {
            val userRef = firestore.collection("users").document(uid)
            val profile = userRef.get().await()
            val favorites = fetchAllCollection(userRef.collection("favorites"))
            val history = fetchAllCollection(userRef.collection("readingHistory"))
            val annotations = fetchAllCollection(userRef.collection("readerAnnotations"))
            val remoteTombstones = fetchAllCollection(userRef.collection("syncTombstones"))
                .mapNotNull { it.toObject(SyncTombstone::class.java) }
            val readerPrefs = userRef.collection("preferences").document("reader").get().await()

            remoteTombstones.forEach { prefs.markSyncTombstone(it.collection, it.documentId, it.deletedAt) }
            val tombstones = newestTombstones(prefs.getSyncTombstones())
            applyTombstones(tombstones)
            favorites.mapNotNull { it.toObject(FavoriteEntity::class.java) }
                .filterNot { isTombstoned("favorites", it.mangaId, it.addedAt, tombstones) }
                .forEach { favoriteDao.insert(it) }
            history.mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }
                .filterNot { isTombstoned("readingHistory", it.mangaId, it.lastReadAt, tombstones) }
                .forEach { historyDao.insertOrUpdate(it) }
            annotations.mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }
                .filterNot { isTombstoned("readerAnnotations", annotationDocId(it), it.updatedAt, tombstones) }
                .forEach { readerAnnotationDao.upsert(it) }

            profile.getString("theme")?.let { name ->
                AppTheme.entries.firstOrNull { it.name == name }?.let { theme ->
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
                com.exapps.mangaworld.domain.model.NotificationDeliveryMode.entries.firstOrNull { it.name == name }?.let { mode ->
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
                com.exapps.mangaworld.domain.model.ReaderMode.entries.firstOrNull { it.name == name }?.let { mode ->
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
                com.exapps.mangaworld.domain.model.ReaderImageFilter.entries.firstOrNull { it.name == name }?.let { filter ->
                    settingsRepository.updateImageFilter(filter)
                }
            }
        }
    }

    suspend fun previewRemoteSnapshot(): CloudRestorePreview {
        val uid = sessionManager.ensureFirebaseSession() ?: error("No user")
        val userRef = firestore.collection("users").document(uid)
        val profile = userRef.get().await()
        val remoteFavorites = fetchAllCollection(userRef.collection("favorites")).mapNotNull { it.toObject(FavoriteEntity::class.java) }
        val remoteHistory = fetchAllCollection(userRef.collection("readingHistory")).mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }
        val remoteAnnotations = fetchAllCollection(userRef.collection("readerAnnotations")).mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }

        val localFavorites = favoriteDao.getFavoritesList()
        val localHistory = historyDao.getAll()
        val localAnnotations = readerAnnotationDao.getAll()
        val localTheme = settingsRepository.getAppSettings().first().theme
        val remoteTheme = profile.getString("theme")?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }

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

    private suspend fun mergeRemoteSnapshot() = syncMutex.withLock {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withLock
        firebaseTelemetry.traceDatabaseSync(operation = "merge") {
            val userRef = firestore.collection("users").document(uid)
            val remoteFavorites = fetchAllCollection(userRef.collection("favorites")).mapNotNull { it.toObject(FavoriteEntity::class.java) }
            val remoteHistory = fetchAllCollection(userRef.collection("readingHistory")).mapNotNull { it.toObject(ReadingHistoryEntity::class.java) }
            val remoteAnnotations = fetchAllCollection(userRef.collection("readerAnnotations")).mapNotNull { it.toObject(ReaderAnnotationEntity::class.java) }
            val remoteTombstones = fetchAllCollection(userRef.collection("syncTombstones"))
                .mapNotNull { it.toObject(SyncTombstone::class.java) }
            remoteTombstones.forEach { prefs.markSyncTombstone(it.collection, it.documentId, it.deletedAt) }
            val tombstones = newestTombstones(prefs.getSyncTombstones())
            applyTombstones(tombstones)
            FirebaseSyncMerge.favorites(favoriteDao.getFavoritesList(), remoteFavorites)
                .filterNot { isTombstoned("favorites", it.mangaId, it.addedAt, tombstones) }
                .forEach { favoriteDao.insert(it) }
            FirebaseSyncMerge.history(historyDao.getAll(), remoteHistory)
                .filterNot { isTombstoned("readingHistory", it.mangaId, it.lastReadAt, tombstones) }
                .forEach { historyDao.insertOrUpdate(it) }
            FirebaseSyncMerge.annotations(readerAnnotationDao.getAll(), remoteAnnotations)
                .filterNot { isTombstoned("readerAnnotations", annotationDocId(it), it.updatedAt, tombstones) }
                .forEach { readerAnnotationDao.upsert(it) }

            val profile = userRef.get().await()
            profile.getString("theme")?.let { name ->
                AppTheme.entries.firstOrNull { it.name == name }?.let { theme -> settingsRepository.updateTheme(theme) }
            }
        }
    }

    private fun annotationDocId(entity: ReaderAnnotationEntity): String =
        FirebaseSyncMerge.annotationDocId(entity)

    private suspend fun commitChunked(writes: List<Pair<com.google.firebase.firestore.DocumentReference, Any>>) {
        val chunks = writes.chunked(400)
        var failedChunks = 0
        for ((index, chunk) in chunks.withIndex()) {
            runCatching {
                val batch: WriteBatch = firestore.batch()
                chunk.forEach { (ref, value) -> batch.set(ref, value, SetOptions.merge()) }
                batch.commit().await()
            }.onFailure { e ->
                android.util.Log.e("FirebaseSync", "Chunk ${index + 1}/${chunks.size} failed: ${e.message}")
                failedChunks++
            }
        }
        if (failedChunks > 0) {
            android.util.Log.w("FirebaseSync", "$failedChunks/${chunks.size} sync chunks failed — will retry on next sync")
        }
    }

    private suspend fun applyTombstones(tombstones: Map<String, SyncTombstone>) {
        tombstones.values.forEach { tombstone ->
            when (tombstone.collection) {
                "favorites" -> favoriteDao.deleteIfOlder(tombstone.documentId, tombstone.deletedAt)
                "readingHistory" -> historyDao.deleteIfOlder(tombstone.documentId, tombstone.deletedAt)
                "readerAnnotations" -> {
                    // annotationDocId = "mangaId_chapterUrlHash_pageIndex"
                    // We can't reverse the chapterUrl hash, so find by matching key and delete atomically
                    val all = readerAnnotationDao.getAll()
                    all.find { annotationDocId(it) == tombstone.documentId && it.updatedAt <= tombstone.deletedAt }
                        ?.let { readerAnnotationDao.deleteIfOlder(it.mangaId, it.chapterUrl, it.pageIndex, tombstone.deletedAt) }
                }
            }
        }
    }

    private fun newestTombstones(tombstones: List<SyncTombstone>): Map<String, SyncTombstone> =
        tombstones.groupBy { it.key }.mapValues { (_, candidates) -> candidates.maxBy { it.deletedAt } }

    private fun isTombstoned(
        collection: String,
        documentId: String,
        updatedAt: Long,
        tombstones: Map<String, SyncTombstone>
    ): Boolean = tombstones["$collection|$documentId"]?.deletedAt?.let { it >= updatedAt } == true

    private fun tombstoneDocumentId(tombstone: SyncTombstone): String =
        "${tombstone.collection}_${tombstone.documentId}".replace("/", "_")
}
