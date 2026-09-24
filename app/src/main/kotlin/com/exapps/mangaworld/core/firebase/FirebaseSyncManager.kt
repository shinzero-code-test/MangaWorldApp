package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.AchievementManager
import com.exapps.mangaworld.core.data.ReadingPositionSyncManager
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.SyncTombstone
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadChapterEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.model.CloudRestorePreview
import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readerAnnotationDao: ReaderAnnotationDao,
    private val readChapterDao: ReadChapterDao,
    private val progressDao: ReadingProgressDao,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager,
    private val prefs: AppPreferences,
    private val readingStatsStore: ReadingStatsStore,
    private val firebaseTelemetry: FirebaseTelemetry,
    private val achievementManager: AchievementManager,
    private val positionSyncManager: ReadingPositionSyncManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val syncMutex = Mutex()

    /** FS-12: UI toggles share one full-library push — coalesce bursts. */
    @Volatile private var lastPushMs = 0L

    /**
     * FS-13: anonymous sessions never touch cloud sync. The anon identity is
     * per-install, so its cloud tree is unreachable from anywhere else —
     * pushing it only burns quota and seeds cross-account contamination.
     * (No session creation here either: sync must not mint anon sessions.)
     */
    private fun namedUid(): String? =
        sessionManager.currentUser()?.takeIf { !it.isAnonymous }?.uid

    /**
     * Push local state to cloud.
     *
     * @param force bypass the UI-burst debounce (worker, startup throttle,
     * manual sync, restore). Plain UI toggles use the debounced path.
     */
    suspend fun pushLocalSnapshot(force: Boolean = false) = syncMutex.withLock {
        if (!force && System.currentTimeMillis() - lastPushMs < PUSH_DEBOUNCE_MS) return@withLock
        val uid = namedUid() ?: return@withLock
        val favorites = favoriteDao.getAllLibraryEntries()
        val history = historyDao.getAll()
        val annotations = readerAnnotationDao.getAll()
        val readMarks = readChapterDao.getAll()
        val tombstones = prefs.getSyncTombstones()
        val settings = settingsRepository.getAppSettings().first()
        val reader = settingsRepository.getReaderSettings().first()

        firebaseTelemetry.traceDatabaseSync(
            operation = "push",
            metrics = mapOf(
                "favorites" to favorites.size.toLong(),
                "history" to history.size.toLong(),
                "annotations" to annotations.size.toLong(),
                "readMarks" to readMarks.size.toLong()
            )
        ) {
            val userRef = firestore.collection("users").document(uid)
            // FS-1: read remote timestamps first — a stale device must never
            // regress newer cloud rows. Ties keep the cloud row (skip).
            val remoteFavTs = fetchAllCollection(userRef.collection("favorites")).docs
                .mapNotNull { FirebaseSyncMerge.favorite(it) }
                .associate { it.mangaId to it.addedAt }
            val remoteHistTs = fetchAllCollection(userRef.collection("readingHistory")).docs
                .mapNotNull { FirebaseSyncMerge.history(it) }
                .associate { it.mangaId to it.lastReadAt }
            val remoteAnnoTs = fetchAllCollection(userRef.collection("readerAnnotations")).docs
                .mapNotNull { FirebaseSyncMerge.annotation(it) }
                .associate { annotationDocId(it) to it.updatedAt }
            val remoteMarkTs = fetchAllCollection(userRef.collection("readMarks")).docs
                .mapNotNull { parseReadMark(it) }
                .associate { (id, _, readAt) -> id to readAt }

            val writes = mutableListOf<Pair<DocumentReference, Any>>()
            // Entity collections ride as explicit maps with OVERWRITE semantics
            // (plain set, no merge): merge would preserve obfuscated junk keys
            // (a..g) from pre-v8.7.1 release POJO writes alongside the clean
            // fields. Overwrite heals those docs on the next push. Votes live
            // in subcollections, which set() never touches.
            writes += userRef to mapOf(
                "updatedAt" to System.currentTimeMillis(),
                "enabledSources" to settings.enabledSources.toList(),
                "theme" to settings.theme.name,
                "useDynamicColors" to settings.useDynamicColors,
                // biometricLockEnabled is device-local (never pushed/pulled, L-review)
                "secureReaderEnabled" to settings.secureReaderEnabled,
                "notificationDeliveryMode" to settings.notificationDeliveryMode.name,
                "autoCleanupReadDownloads" to settings.autoCleanupReadDownloads,
                "cleanupAfterHours" to settings.cleanupAfterHours,
                "imageCacheLimitMb" to settings.imageCacheLimitMb,
                "contentBlacklist" to settings.contentBlacklist.take(200).toList(),
                "spoilerCollapseDefault" to settings.spoilerCollapseDefault,
                "mutedUserIds" to settings.mutedUserIds.take(100).toList(),
                // Sync reading stats so they survive device switches
                "totalReadingTimeMs" to readingStatsStore.totalReadingTimeMs.first(),
                "totalMangaRead" to readingStatsStore.totalMangaRead.first()
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
            val overwrites = mutableListOf<Pair<DocumentReference, Any>>()
            favorites.forEach { favorite ->
                if (shouldPushLocal(favorite.addedAt, remoteFavTs[favorite.mangaId])) {
                    overwrites += userRef.collection("favorites").document(favorite.mangaId) to
                        FirebaseSyncMerge.favoriteToMap(favorite)
                }
            }
            history.forEach { item ->
                if (shouldPushLocal(item.lastReadAt, remoteHistTs[item.mangaId])) {
                    overwrites += userRef.collection("readingHistory").document(item.mangaId) to
                        FirebaseSyncMerge.historyToMap(item)
                }
            }
            annotations.forEach { annotation ->
                val key = annotationDocId(annotation)
                if (shouldPushLocal(annotation.updatedAt, remoteAnnoTs[key])) {
                    overwrites += userRef.collection("readerAnnotations").document(key) to
                        FirebaseSyncMerge.annotationToMap(annotation)
                }
            }
            readMarks.forEach { mark ->
                val key = FirebaseSyncMerge.readMarkDocId(mark.mangaId, mark.chapterNumber)
                if (shouldPushLocal(mark.readAt, remoteMarkTs[key])) {
                    writes += userRef.collection("readMarks").document(key) to mapOf(
                        "mangaId" to mark.mangaId,
                        "chapterNumber" to mark.chapterNumber.toDouble(),
                        "readAt" to mark.readAt
                    )
                }
            }
            tombstones.forEach { tombstone ->
                writes += userRef.collection("syncTombstones").document(tombstoneDocumentId(tombstone)) to tombstone
            }

            // Sync achievements and goals
            runCatching {
                val achievementsData = achievementManager.syncToFirestoreMap()
                writes += userRef.collection("preferences").document("achievements") to achievementsData
            }

            commitChunked(writes)
            // Entity snapshots overwrite (see above) — merged writes would keep
            // the obfuscated legacy keys alive next to the clean fields.
            commitOverwrite(overwrites)
            // Tombstoned rows must disappear from cloud too — otherwise new
            // devices resurrect them on first pull (their tombstones are local).
            commitChunkedDeletes(tombstones.map { userRef.collection(it.collection).document(it.documentId) })
            // FS-10: expired cloud tombstones are dead weight (local copies
            // pruned at TTL) — reap them so the 10k cap never drops live ones.
            pruneExpiredCloudTombstones(userRef)
        }
        lastPushMs = System.currentTimeMillis()
    }

    /** FS-1 guard: push only rows absent remotely or strictly newer (ties keep cloud). */
    private fun shouldPushLocal(localTs: Long, remoteTs: Long?): Boolean =
        remoteTs == null || localTs > remoteTs

    /**
     * Fetch all documents from a Firestore collection using cursor-based pagination.
     * Caps at [maxDocs] total to prevent unbounded reads for power users.
     */
    private suspend fun fetchAllCollection(
        ref: CollectionReference,
        maxDocs: Int = 10_000
    ): FetchResult {
        val result = mutableListOf<DocumentSnapshot>()
        var lastDocId: String? = null
        var truncated = false
        while (result.size < maxDocs) {
            val pageSize = minOf(1000, maxDocs - result.size)
            val query = if (lastDocId != null) {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId())
                    .startAfter(lastDocId).limit(pageSize.toLong())
            } else {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId()).limit(pageSize.toLong())
            }
            val snap = query.get().await()
            if (snap.isEmpty) break
            result.addAll(snap.documents)
            lastDocId = snap.documents.lastOrNull()?.id ?: break
            if (snap.size() < pageSize) break
            if (result.size >= maxDocs) truncated = true
        }
        return FetchResult(result, truncated)
    }

    private data class FetchResult(val docs: List<DocumentSnapshot>, val truncated: Boolean)

    suspend fun pullRemoteSnapshot() = syncMutex.withLock {
        val uid = namedUid() ?: return@withLock
        firebaseTelemetry.traceDatabaseSync(operation = "pull") {
            val userRef = firestore.collection("users").document(uid)
            val profile = userRef.get().await()
            val favorites = fetchAllCollection(userRef.collection("favorites")).docs
            val history = fetchAllCollection(userRef.collection("readingHistory")).docs
            val annotations = fetchAllCollection(userRef.collection("readerAnnotations")).docs
            val readMarks = fetchAllCollection(userRef.collection("readMarks")).docs
            val remoteTombstones = fetchAllCollection(userRef.collection("syncTombstones")).docs
                .mapNotNull { it.toObject(SyncTombstone::class.java) }
            val readerPrefs = userRef.collection("preferences").document("reader").get().await()

            remoteTombstones.forEach { prefs.markSyncTombstone(it.collection, it.documentId, it.deletedAt) }
            val tombstones = newestTombstones(prefs.getSyncTombstones())
            applyTombstones(tombstones)
            favorites.mapNotNull { FirebaseSyncMerge.favorite(it) }
                .filterNot { isTombstoned("favorites", it.mangaId, it.addedAt, tombstones) }
                .forEach { favoriteDao.insert(it) }
            history.mapNotNull { FirebaseSyncMerge.history(it) }
                .filterNot { isTombstoned("readingHistory", it.mangaId, it.lastReadAt, tombstones) }
                .forEach { historyDao.insertOrUpdate(it) }
            annotations.mapNotNull { FirebaseSyncMerge.annotation(it) }
                .filterNot { isAnnotationTombstoned(it, tombstones) }
                .forEach { readerAnnotationDao.upsert(it) }
            // FS-7: read marks ride along (union — read is monotonic; unmarks
            // arrive as tombstones and are filtered here).
            readMarks.mapNotNull { parseReadMark(it) }
                .filterNot { (id, _, readAt) -> isTombstoned("readMarks", id, readAt, tombstones) }
                .forEach { (_, entity, _) -> readChapterDao.markRead(entity) }

            applyUserDocSettings(profile)
            applyReaderSettings(readerPrefs)
            applyStatsAndAchievements(userRef)
        }
    }

    suspend fun previewRemoteSnapshot(): CloudRestorePreview {
        val uid = namedUid() ?: error("No user")
        val userRef = firestore.collection("users").document(uid)
        val profile = userRef.get().await()
        val remoteFavorites = fetchAllCollection(userRef.collection("favorites"))
        val remoteHistory = fetchAllCollection(userRef.collection("readingHistory"))
        val remoteAnnotations = fetchAllCollection(userRef.collection("readerAnnotations"))
        val remoteReadMarks = fetchAllCollection(userRef.collection("readMarks"))
        val parsedFavorites = remoteFavorites.docs.mapNotNull { FirebaseSyncMerge.favorite(it) }
        val parsedHistory = remoteHistory.docs.mapNotNull { FirebaseSyncMerge.history(it) }
        val parsedAnnotations = remoteAnnotations.docs.mapNotNull { FirebaseSyncMerge.annotation(it) }

        val localFavorites = favoriteDao.getAllLibraryEntries()
        val localHistory = historyDao.getAll()
        val localAnnotations = readerAnnotationDao.getAll()
        val localReadMarks = readChapterDao.getAll()
        val localTheme = settingsRepository.getAppSettings().first().theme
        val remoteTheme = profile.getString("theme")?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }

        val localLatest = localHistory.maxOfOrNull { it.lastReadAt } ?: 0L
        val remoteLatest = parsedHistory.maxOfOrNull { it.lastReadAt } ?: 0L
        val localLatestAnnotation = localAnnotations.maxOfOrNull { it.updatedAt } ?: 0L
        val remoteLatestAnnotation = parsedAnnotations.maxOfOrNull { it.updatedAt } ?: 0L
        val strategy = suggestCloudRestoreStrategy(
            localFavorites = localFavorites.size,
            remoteFavorites = parsedFavorites.size,
            localLatestHistoryAt = localLatest,
            remoteLatestHistoryAt = remoteLatest,
            localLatestAnnotationAt = localLatestAnnotation,
            remoteLatestAnnotationAt = remoteLatestAnnotation
        )
        return CloudRestorePreview(
            localFavorites = localFavorites.size,
            remoteFavorites = parsedFavorites.size,
            localHistory = localHistory.size,
            remoteHistory = parsedHistory.size,
            localAnnotations = localAnnotations.size,
            remoteAnnotations = parsedAnnotations.size,
            localReadMarks = localReadMarks.size,
            remoteReadMarks = remoteReadMarks.docs.size,
            localLatestHistoryAt = localLatest,
            remoteLatestHistoryAt = remoteLatest,
            localLatestAnnotationAt = localLatestAnnotation,
            remoteLatestAnnotationAt = remoteLatestAnnotation,
            remoteTheme = remoteTheme,
            localTheme = localTheme,
            // FS-11: never silently truncate — the UI warns when capped.
            truncated = remoteFavorites.truncated || remoteHistory.truncated ||
                remoteAnnotations.truncated || remoteReadMarks.truncated,
            suggestedStrategy = strategy
        )
    }

    suspend fun applyRemoteRestore(strategy: CloudRestoreStrategy) {
        when (strategy) {
            CloudRestoreStrategy.KEEP_LOCAL -> pushKeepLocal()
            CloudRestoreStrategy.REMOTE_OVERWRITE -> pullRemoteOverwrite()
            CloudRestoreStrategy.MERGE -> mergeRemoteSnapshot()
        }
    }

    /**
     * FS-2: KEEP_LOCAL truly keeps local — cloud docs absent locally are
     * deleted (with tombstones so other devices converge), then a forced
     * guarded push uploads the rest. Legacy-keyed annotation dups are
     * collected as migration cleanup.
     */
    private suspend fun pushKeepLocal() {
        val sweepDone = syncMutex.withLock {
            val uid = namedUid() ?: return@withLock false
            val userRef = firestore.collection("users").document(uid)
            val now = System.currentTimeMillis()
            val localFavIds = favoriteDao.getAllLibraryEntries().map { it.mangaId }.toSet()
            val localHistIds = historyDao.getAll().map { it.mangaId }.toSet()
            val localAnnoIds = readerAnnotationDao.getAll().map { annotationDocId(it) }.toSet()
            val localMarkIds = readChapterDao.getAll()
                .map { FirebaseSyncMerge.readMarkDocId(it.mangaId, it.chapterNumber) }.toSet()

            val staleCloud = mutableListOf<Pair<String, String>>()
            suspend fun sweep(collection: String, localIds: Set<String>) {
                runCatching {
                    fetchAllCollection(userRef.collection(collection)).docs.forEach { doc ->
                        val key = when (collection) {
                            "favorites" -> FirebaseSyncMerge.favorite(doc)?.mangaId
                            "readingHistory" -> FirebaseSyncMerge.history(doc)?.mangaId
                            "readerAnnotations" -> FirebaseSyncMerge.annotation(doc)?.let { annotationDocId(it) }
                            "readMarks" -> parseReadMark(doc)?.first
                            else -> null
                        } ?: doc.id
                        // Legacy annotation dup: same content, old key — collect
                        // without tombstoning (the new-keyed twin is the truth).
                        if (collection == "readerAnnotations" && key in localIds && doc.id != key) {
                            staleCloud += collection to doc.id
                        } else if (key !in localIds) {
                            staleCloud += collection to key
                            runCatching { prefs.markSyncTombstone(collection, key, now) }
                        }
                    }
                }
            }
            sweep("favorites", localFavIds)
            sweep("readingHistory", localHistIds)
            sweep("readerAnnotations", localAnnoIds)
            sweep("readMarks", localMarkIds)
            if (staleCloud.isNotEmpty()) {
                commitChunkedDeletes(staleCloud.map { (collection, id) -> userRef.collection(collection).document(id) })
            }
            // Positions are fully rewritten from local (+ stale chunks removed).
            runCatching { positionSyncManager.pushLocalPositions() }
            true
        }
        if (sweepDone) {
            // Forced guarded push uploads local truth + the new tombstone docs.
            pushLocalSnapshot(force = true)
        }
    }

    /**
     * FS-2: REMOTE_OVERWRITE truly overwrites — local-only rows are deleted
     * (with tombstones so the next push propagates the deletion cloud-wide),
     * then the pull inserts the remote snapshot. Positions are replaced.
     */
    private suspend fun pullRemoteOverwrite() {
        val pulled = syncMutex.withLock {
            val uid = namedUid() ?: return@withLock false
            val userRef = firestore.collection("users").document(uid)
            val now = System.currentTimeMillis()
            val remoteFavIds = fetchAllCollection(userRef.collection("favorites")).docs
                .mapNotNull { FirebaseSyncMerge.favorite(it) }.map { it.mangaId }.toSet()
            val remoteHistIds = fetchAllCollection(userRef.collection("readingHistory")).docs
                .mapNotNull { FirebaseSyncMerge.history(it) }.map { it.mangaId }.toSet()
            val remoteAnnoDocs = fetchAllCollection(userRef.collection("readerAnnotations")).docs
            val remoteAnnoIds = remoteAnnoDocs
                .mapNotNull { FirebaseSyncMerge.annotation(it) }.map { annotationDocId(it) }.toSet()
            val remoteAnnoRawIds = remoteAnnoDocs.map { it.id }.toSet()
            val remoteMarkIds = fetchAllCollection(userRef.collection("readMarks")).docs
                .mapNotNull { parseReadMark(it) }.map { it.first }.toSet()

            favoriteDao.getAllLibraryEntries()
                .filterNot { it.mangaId in remoteFavIds }
                .forEach {
                    favoriteDao.deleteIfOlder(it.mangaId, Long.MAX_VALUE)
                    runCatching { prefs.markSyncTombstone("favorites", it.mangaId, now) }
                }
            historyDao.getAll()
                .filterNot { it.mangaId in remoteHistIds }
                .forEach {
                    historyDao.delete(it.mangaId)
                    runCatching { prefs.markSyncTombstone("readingHistory", it.mangaId, now) }
                }
            readerAnnotationDao.getAll()
                .filterNot {
                    annotationDocId(it) in remoteAnnoIds ||
                        FirebaseSyncMerge.legacyAnnotationDocId(it) in remoteAnnoRawIds
                }
                .forEach {
                    readerAnnotationDao.delete(it.mangaId, it.chapterUrl, it.pageIndex)
                    runCatching { prefs.markSyncTombstone("readerAnnotations", annotationDocId(it), now) }
                }
            readChapterDao.getAll()
                .filterNot { FirebaseSyncMerge.readMarkDocId(it.mangaId, it.chapterNumber) in remoteMarkIds }
                .forEach {
                    readChapterDao.markUnread(it.mangaId, it.chapterNumber)
                    runCatching {
                        prefs.markSyncTombstone(
                            "readMarks",
                            FirebaseSyncMerge.readMarkDocId(it.mangaId, it.chapterNumber),
                            now
                        )
                    }
                }
            progressDao.clearAll()
            true
        }
        if (pulled) {
            pullRemoteSnapshot()
            // Positions have no tombstones (write-only locally) — replace outright.
            runCatching { positionSyncManager.pullRemotePositions() }
        }
    }

    /** Merge remote state into local (safe both directions — last-write-wins + tombstones). */
    suspend fun mergeRemoteSnapshot() = syncMutex.withLock {
        val uid = namedUid() ?: return@withLock
        firebaseTelemetry.traceDatabaseSync(operation = "merge") {
            val userRef = firestore.collection("users").document(uid)
            val remoteFavorites = fetchAllCollection(userRef.collection("favorites")).docs
                .mapNotNull { FirebaseSyncMerge.favorite(it) }
            val remoteHistory = fetchAllCollection(userRef.collection("readingHistory")).docs
                .mapNotNull { FirebaseSyncMerge.history(it) }
            val remoteAnnotations = fetchAllCollection(userRef.collection("readerAnnotations")).docs
                .mapNotNull { FirebaseSyncMerge.annotation(it) }
            val remoteReadMarks = fetchAllCollection(userRef.collection("readMarks")).docs
                .mapNotNull { parseReadMark(it) }
            val remoteTombstones = fetchAllCollection(userRef.collection("syncTombstones")).docs
                .mapNotNull { it.toObject(SyncTombstone::class.java) }
            remoteTombstones.forEach { prefs.markSyncTombstone(it.collection, it.documentId, it.deletedAt) }
            val tombstones = newestTombstones(prefs.getSyncTombstones())
            applyTombstones(tombstones)
            FirebaseSyncMerge.favorites(favoriteDao.getAllLibraryEntries(), remoteFavorites)
                .filterNot { isTombstoned("favorites", it.mangaId, it.addedAt, tombstones) }
                .forEach { favoriteDao.insert(it) }
            FirebaseSyncMerge.history(historyDao.getAll(), remoteHistory)
                .filterNot { isTombstoned("readingHistory", it.mangaId, it.lastReadAt, tombstones) }
                .forEach { historyDao.insertOrUpdate(it) }
            FirebaseSyncMerge.annotations(readerAnnotationDao.getAll(), remoteAnnotations)
                .filterNot { isAnnotationTombstoned(it, tombstones) }
                .forEach { readerAnnotationDao.upsert(it) }
            // FS-7: union read marks (read is monotonic; unmarks arrive as
            // tombstones and are filtered here).
            val localMarkIds = readChapterDao.getAll()
                .associate { FirebaseSyncMerge.readMarkDocId(it.mangaId, it.chapterNumber) to it.readAt }
            remoteReadMarks
                .filterNot { (id, _, readAt) -> isTombstoned("readMarks", id, readAt, tombstones) }
                .forEach { (id, entity, readAt) ->
                    val localTs = localMarkIds[id]
                    if (localTs == null) {
                        readChapterDao.markRead(entity)
                    } else if (readAt > localTs) {
                        // Same mark, newer cloud timestamp — refresh readAt.
                        readChapterDao.markRead(entity)
                    }
                }

            // FS-6: merge applies the full settings/stats/achievements picture,
            // not just the theme (shared appliers with the pull path).
            val profile = userRef.get().await()
            applyUserDocSettings(profile)
            val readerPrefs = userRef.collection("preferences").document("reader").get().await()
            applyReaderSettings(readerPrefs)
            applyStatsAndAchievements(userRef)
        }
    }

    /**
     * FS-6: user-doc settings applier shared by pull + merge (previously the
     * merge path applied only the theme).
     */
    private suspend fun applyUserDocSettings(profile: DocumentSnapshot) {
        profile.getString("theme")?.let { name ->
            AppTheme.entries.firstOrNull { it.name == name }?.let { theme ->
                settingsRepository.updateTheme(theme)
            }
        }
        (profile.get("enabledSources") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()?.let { sourceIds ->
            // Drop unknown IDs (hijacked/corrupt docs must not plant phantom sources).
            val known = com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds.ALL
            settingsRepository.setEnabledSources(sourceIds.intersect(known))
        }
        profile.getBoolean("useDynamicColors")?.let { settingsRepository.setDynamicColors(it) }
        // Security-sensitive: biometric lock is DEVICE-LOCAL and never pulled
        // from cloud — a hijacked session must not be able to disable it (L-review).
        // profile.getBoolean("biometricLockEnabled") intentionally not applied.
        profile.getBoolean("secureReaderEnabled")?.let { settingsRepository.setSecureReader(it) }
        profile.getString("notificationDeliveryMode")?.let { name ->
            com.exapps.mangaworld.domain.model.NotificationDeliveryMode.entries.firstOrNull { it.name == name }?.let { mode ->
                settingsRepository.setNotificationDeliveryMode(mode)
            }
        }
        profile.getBoolean("autoCleanupReadDownloads")?.let { settingsRepository.setAutoCleanupReadDownloads(it) }
        profile.getLong("cleanupAfterHours")?.toInt()?.let { settingsRepository.setCleanupAfterHours(it) }
        profile.getLong("imageCacheLimitMb")?.toInt()?.let { settingsRepository.setImageCacheLimitMb(it) }
        (profile.get("contentBlacklist") as? List<*>)?.mapNotNull { it?.toString() }?.take(200)?.toSet()?.let { blacklist ->
            settingsRepository.setContentBlacklist(blacklist)
        }
        profile.getBoolean("spoilerCollapseDefault")?.let { settingsRepository.setSpoilerCollapseDefault(it) }
        (profile.get("mutedUserIds") as? List<*>)?.mapNotNull { it?.toString() }?.take(100)?.toSet()?.let { muted ->
            settingsRepository.setMutedUserIds(muted)
        }
    }

    /**
     * FS-6: reader-settings applier shared by pull + merge — including the 6
     * fields the old pull silently dropped (pageSpacing, showPageNumber,
     * dualPageLandscape, webtoonAutoStitch, volumeButtonPageTurn,
     * doubleTapZoom).
     */
    private suspend fun applyReaderSettings(readerPrefs: DocumentSnapshot) {
        readerPrefs.getString("mode")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderMode.entries.firstOrNull { it.name == name }?.let { mode ->
                settingsRepository.updateReaderMode(mode)
            }
        }
        readerPrefs.getDouble("brightness")?.toFloat()?.let { settingsRepository.updateBrightness(it) }
        readerPrefs.getLong("pageSpacing")?.toInt()?.let { settingsRepository.updatePageSpacing(it) }
        readerPrefs.getBoolean("keepScreenOn")?.let { settingsRepository.updateKeepScreenOn(it) }
        readerPrefs.getBoolean("showPageNumber")?.let { settingsRepository.updateShowPageNumber(it) }
        readerPrefs.getBoolean("autoWebtoonDetection")?.let { settingsRepository.updateAutoWebtoon(it) }
        readerPrefs.getBoolean("incognitoMode")?.let { settingsRepository.updateIncognitoMode(it) }
        readerPrefs.getBoolean("smartPrefetchEnabled")?.let { settingsRepository.updateSmartPrefetch(it) }
        readerPrefs.getBoolean("hapticsEnabled")?.let { settingsRepository.updateReaderHaptics(it) }
        readerPrefs.getBoolean("autoOpenNextChapter")?.let { settingsRepository.updateAutoOpenNextChapter(it) }
        readerPrefs.getBoolean("showLiveReadersOverlay")?.let { settingsRepository.updateShowLiveReadersOverlay(it) }
        readerPrefs.getBoolean("showReactionOverlay")?.let { settingsRepository.updateShowReactionOverlay(it) }
        readerPrefs.getBoolean("dualPageLandscape")?.let { settingsRepository.updateDualPageLandscape(it) }
        readerPrefs.getBoolean("webtoonAutoStitch")?.let { settingsRepository.updateWebtoonAutoStitch(it) }
        readerPrefs.getBoolean("volumeButtonPageTurn")?.let { settingsRepository.updateVolumeButtonPageTurn(it) }
        readerPrefs.getBoolean("doubleTapZoom")?.let { settingsRepository.updateDoubleTapZoom(it) }
        readerPrefs.getString("imageFilter")?.let { name ->
            com.exapps.mangaworld.domain.model.ReaderImageFilter.entries.firstOrNull { it.name == name }?.let { filter ->
                settingsRepository.updateImageFilter(filter)
            }
        }
    }

    /**
     * FS-6/FS-8: stats + achievements applier shared by pull + merge.
     * Counters max-merge (monotonic) and achievements union-merge, so pulling
     * after offline progress can never destroy it.
     */
    private suspend fun applyStatsAndAchievements(userRef: com.google.firebase.firestore.DocumentReference) {
        // FS-8: totals were pushed but never pulled — new devices started at zero.
        val profile = userRef.get().await()
        val statsJson = JSONObject()
        profile.getLong("totalReadingTimeMs")?.let { statsJson.put("totalReadingTimeMs", it) }
        // Pushed as Int (stored Long) — tolerate both.
        (profile.getLong("totalMangaRead") ?: (profile.getDouble("totalMangaRead"))?.toInt()?.toLong())
            ?.let { statsJson.put("totalMangaRead", it.toInt()) }
        if (statsJson.length() > 0) {
            runCatching { readingStatsStore.restore(statsJson) }
        }
        // Pull achievements and goals from Firestore (max/union merge).
        runCatching {
            val achievementsDoc = userRef.collection("preferences").document("achievements").get().await()
            if (achievementsDoc.exists()) {
                achievementManager.importFromFirestore(
                    totalPagesRead = achievementsDoc.getLong("totalPagesRead")?.toInt() ?: 0,
                    totalChaptersRead = achievementsDoc.getLong("totalChaptersRead")?.toInt() ?: 0,
                    goalsJson = achievementsDoc.getString("goals") ?: "[]",
                    achievementsJson = achievementsDoc.getString("achievements") ?: "[]"
                )
            }
        }
    }

    private fun annotationDocId(entity: ReaderAnnotationEntity): String =
        FirebaseSyncMerge.annotationDocId(entity)

    /** Tolerantly parse a readMarks doc: (docId, entity, readAt) or null. */
    private fun parseReadMark(doc: DocumentSnapshot): Triple<String, ReadChapterEntity, Long>? = runCatching {
        val mangaId = doc.getString("mangaId")?.takeIf { it.isNotBlank() } ?: return null
        val chapter = doc.getDouble("chapterNumber")?.toFloat() ?: return null
        val readAt = doc.getLong("readAt") ?: 0L
        Triple(
            FirebaseSyncMerge.readMarkDocId(mangaId, chapter),
            ReadChapterEntity(mangaId, chapter, readAt),
            readAt
        )
    }.getOrNull()

    private suspend fun commitChunked(writes: List<Pair<DocumentReference, Any>>) {
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
            // Partial push must NOT report success: the worker maps this to retry,
            // and silent success would park lost chunks behind the hourly throttle.
            error("FirebaseSync: $failedChunks/${chunks.size} sync chunks failed")
        }
    }

    /**
     * Plain-set variant for full entity snapshots (favorites/history/
     * annotations). Unlike [commitChunked] this does NOT merge, so legacy
     * obfuscated keys from pre-v8.7.1 release POJO writes are dropped instead
     * of preserved next to the clean fields. Same fail-loud contract.
     */
    private suspend fun commitOverwrite(writes: List<Pair<DocumentReference, Any>>) {
        if (writes.isEmpty()) return
        val chunks = writes.chunked(400)
        var failedChunks = 0
        for ((index, chunk) in chunks.withIndex()) {
            runCatching {
                val batch: WriteBatch = firestore.batch()
                chunk.forEach { (ref, value) -> batch.set(ref, value) }
                batch.commit().await()
            }.onFailure { e ->
                android.util.Log.e("FirebaseSync", "Overwrite chunk ${index + 1}/${chunks.size} failed: ${e.message}")
                failedChunks++
            }
        }
        if (failedChunks > 0) {
            error("FirebaseSync: $failedChunks/${chunks.size} sync overwrite chunks failed")
        }
    }

    /**
     * FS-10: reap expired cloud tombstone docs (best-effort — never fails the
     * push). Local copies prune at TTL; without this the 10k fetch cap would
     * eventually drop live tombstones in favor of dead ones.
     */
    private suspend fun pruneExpiredCloudTombstones(userRef: DocumentReference) {
        runCatching {
            val cutoff = System.currentTimeMillis() - AppPreferences.TOMBSTONE_TTL_MS
            userRef.collection("syncTombstones")
                .whereLessThan("deletedAt", cutoff).limit(100).get().await()
                .documents.forEach { runCatching { it.reference.delete().await() } }
        }
    }

    /** Fail-loud batched deletes (tombstone application, restore sweeps). */    private suspend fun commitChunkedDeletes(refs: List<DocumentReference>) {
        if (refs.isEmpty()) return
        val chunks = refs.chunked(400)
        var failedChunks = 0
        for ((index, chunk) in chunks.withIndex()) {
            runCatching {
                val batch: WriteBatch = firestore.batch()
                chunk.forEach { batch.delete(it) }
                batch.commit().await()
            }.onFailure { e ->
                android.util.Log.e("FirebaseSync", "Delete chunk ${index + 1}/${chunks.size} failed: ${e.message}")
                failedChunks++
            }
        }
        if (failedChunks > 0) {
            error("FirebaseSync: $failedChunks/${chunks.size} delete chunks failed")
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
                    all.find { (annotationDocId(it) == tombstone.documentId || FirebaseSyncMerge.legacyAnnotationDocId(it) == tombstone.documentId) && it.updatedAt <= tombstone.deletedAt }
                        ?.let { readerAnnotationDao.deleteIfOlder(it.mangaId, it.chapterUrl, it.pageIndex, tombstone.deletedAt) }
                }
                // FS-7: read-mark tombstones (unmark propagates like any delete).
                "readMarks" -> {
                    val (mangaId, chapter) = FirebaseSyncMerge.parseReadMarkDocId(tombstone.documentId)
                        ?: return@forEach
                    val marks = readChapterDao.getAll()
                    marks.find {
                        it.mangaId == mangaId && it.chapterNumber == chapter && it.readAt <= tombstone.deletedAt
                    }?.let { readChapterDao.markUnread(mangaId, chapter) }
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

    /**
     * FS-9: annotations match tombstones on BOTH id schemes — the old pull
     * filter only checked the new scheme, resurrecting legacy deletions.
     */
    private fun isAnnotationTombstoned(
        entity: ReaderAnnotationEntity,
        tombstones: Map<String, SyncTombstone>
    ): Boolean = isTombstoned("readerAnnotations", annotationDocId(entity), entity.updatedAt, tombstones) ||
        isTombstoned(
            "readerAnnotations",
            FirebaseSyncMerge.legacyAnnotationDocId(entity),
            entity.updatedAt,
            tombstones
        )

    private fun tombstoneDocumentId(tombstone: SyncTombstone): String =
        "${tombstone.collection}_${tombstone.documentId}".replace("/", "_")

    private companion object {
        /** FS-12: UI-toggle pushes closer than this are coalesced (force bypasses). */
        const val PUSH_DEBOUNCE_MS = 60_000L
    }
}
