package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStartupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readerAnnotationDao: ReaderAnnotationDao,
    private val readChapterDao: ReadChapterDao,
    private val progressDao: ReadingProgressDao,
    private val prefs: AppPreferences,
    private val topicManager: FirebaseTopicManager,
    private val messagingRegistrar: FirebaseMessagingRegistrar,
    private val userInsightsCoordinator: FirebaseUserInsightsCoordinator,
    private val notificationPolicyManager: NotificationPolicyManager,
    private val telemetry: FirebaseTelemetry
) {
    private val prefsStore by lazy {
        context.getSharedPreferences("firebase_startup_prefs", Context.MODE_PRIVATE)
    }

    suspend fun initialize() {
        val uid = sessionManager.ensureFirebaseSession()
        telemetry.setCrashlyticsUserId(uid)

        // Run independent operations in parallel to reduce cold-start latency
        coroutineScope {
            val insightsJob = async { userInsightsCoordinator.start() }
            val configJob = async { remoteConfigManager.refresh() }
            val tokenJob = async { runCatching { messagingRegistrar.syncCurrentToken() } }
            insightsJob.await()
            configJob.await()
            tokenJob.await()
        }

        if (uid != null) {
            // FS-3: isolate accounts — a named→named switch wipes the previous
            // account's library before anything can push it into the new tree.
            handleAccountSwitch(uid)
        }

        // FS-3: all sync flags are per-UID — a new account gets its own first
        // merge and its own push throttle instead of inheriting the old one.
        val pushKey = "last_push_sync_$uid"
        val mergeKey = "initial_merge_done_$uid"

        // Item 6: first launch with a session pulls remote state once, so
        // history/favorites appear on a second device without a manual restore.
        // Merge (never overwrite) + tombstones make this safe in both directions.
        // Fresh UIDs merge BEFORE any push so the first push can't upload a
        // stranger's library over the new account's cloud (FS-3).
        if (uid != null && !prefsStore.getBoolean(mergeKey, false)) {
            if (runCatching { syncManager.mergeRemoteSnapshot() }.isSuccess) {
                prefsStore.edit().putBoolean(mergeKey, true).apply()
            }
        }

        // Throttle full sync to once per hour to avoid redundant Firestore writes on every app launch
        val lastSync = prefsStore.getLong(pushKey, 0L)
        val now = System.currentTimeMillis()
        if (uid != null && now - lastSync > 3_600_000L) { // 1 hour
            // Stamp only on success (#26): stamping a failed push would
            // suppress the retry until the next hour window.
            if (runCatching { syncManager.pushLocalSnapshot(force = true) }.isSuccess) {
                prefsStore.edit().putLong(pushKey, now).apply()
            }
        }

        runCatching {
            // Bounded concurrency: N serial subscribeToTopic round-trips stall
            // cold start with 100+ favorites. Failures stay logged in the manager.
            val favorites = favoriteDao.getFavoritesList()
            coroutineScope {
                favorites.chunked(10).forEach { chunk ->
                    chunk.map { fav ->
                        async { topicManager.subscribeToManga(fav.mangaId) }
                    }.awaitAll()
                }
            }
        }
        runCatching { notificationPolicyManager.checkAndSendReminders() }
    }

    /**
     * FS-3: detect account switches. Only a named→different-named transition
     * wipes the local library — sign-out→guest and guest→named keep it (the
     * guest library migrates via merge + push, and wiping on sign-out would
     * strand users with an empty library).
     */
    private suspend fun handleAccountSwitch(uid: String) {
        val lastUid = prefsStore.getString(KEY_LAST_UID, null)
        val lastNamed = prefsStore.getBoolean(KEY_LAST_NAMED, false)
        val named = sessionManager.currentUser()?.takeIf { !it.isAnonymous }?.uid == uid
        if (lastUid != null && lastUid != uid && lastNamed && named) {
            runCatching {
                favoriteDao.clearAll()
                historyDao.clearAll()
                readerAnnotationDao.clearAll()
                readChapterDao.clearAll()
                progressDao.clearAll()
                prefs.clearSyncTombstones()
            }
        }
        prefsStore.edit()
            .putString(KEY_LAST_UID, uid)
            .putBoolean(KEY_LAST_NAMED, named)
            .apply()
    }

    private companion object {
        const val KEY_LAST_UID = "last_sync_uid"
        const val KEY_LAST_NAMED = "last_sync_named"
    }
}
