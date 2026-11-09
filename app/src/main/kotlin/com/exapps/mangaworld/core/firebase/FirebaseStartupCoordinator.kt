package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.util.Log
import com.exapps.mangaworld.core.data.AchievementManager
import com.exapps.mangaworld.core.data.BookmarkManager
import com.exapps.mangaworld.core.data.CollectionManager
import com.exapps.mangaworld.core.data.LocalBackupManager
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.util.suspendRunCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
    private val telemetry: FirebaseTelemetry,
    private val backupManager: LocalBackupManager,
    private val readingStatsStore: ReadingStatsStore,
    private val achievementManager: AchievementManager,
    private val collectionManager: CollectionManager,
    private val bookmarkManager: BookmarkManager
) {
    /**
     * U-09: confirmation gate for a named→named switch when the local
     * library is non-empty. The callback must return true to proceed with
     * the wipe. Null (default) proceeds immediately — the pre-wipe
     * snapshot + retry remains the safety core. Production UI wiring
     * (MainActivity dialog) is pending; see
     * tmp/review/stage1/09-account-switch.md.
     */
    @Volatile
    var onAccountSwitchRequiresConfirmation: (suspend () -> Boolean)? = null
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
            // A failed/aborted wipe (U-09) aborts initialize before merge/push —
            // merging or pushing a stale library would contaminate the new uid.
            // The next launch retries (flags stay on the old uid/bytes).
            if (!handleAccountSwitch(uid)) return
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
        // U-10: gate the first forced push on merge success (below) — a
        // fresh/wiped device with a failed merge never uploads stale
        // settings over the cloud truth.
        val mergeJustSucceeded = uid != null && !prefsStore.getBoolean(mergeKey, false) &&
            runCatching { syncManager.mergeRemoteSnapshot() }.isSuccess
        if (mergeJustSucceeded) {
            prefsStore.edit().putBoolean(mergeKey, true).apply()
        }
        val merged = prefsStore.getBoolean(mergeKey, false) || mergeJustSucceeded

        // Throttle full sync to once per hour to avoid redundant Firestore writes on every app launch
        val lastSync = prefsStore.getLong(pushKey, 0L)
        val now = System.currentTimeMillis()
        if (uid != null && merged && now - lastSync > 3_600_000L) { // 1 hour
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
     * wipes the local library — guest→named migrates the guest library
     * (merge + push), and named→anonymous sign-out must not flip to a null/
     * anonymous new-UID wipe: sign-out pairing is a legal identity migration
     * that keeps the library (see [isNamedSwitch] branch below).
     *
     * U-09: the KEY_LAST_UID/KEY_LAST_NAMED flags advance only after a
     * successful wipe (or on a non-switch path). On wipe/snapshot/confirm
     * failure this returns false without writing flags, so the next
     * initialize() call still sees the old uid/bytes and retries the full
     * gate → snapshot → wipe. There is deliberately no
     * `if (lastUid == uid) return` early-exit: it would return before the
     * retry condition is evaluated and must never treat a failed wipe as
     * switched — the unchanged-flag re-evaluation is the retry.
     *
     * @return true when initialize() may proceed to merge/push, false to abort.
     */
    private suspend fun handleAccountSwitch(uid: String): Boolean {
        val lastUid = prefsStore.getString(KEY_LAST_UID, null)
        val lastNamed = prefsStore.getBoolean(KEY_LAST_NAMED, false)
        val named = sessionManager.currentUser()?.takeIf { !it.isAnonymous }?.uid == uid
        val isNamedSwitch = lastUid != null && lastUid != uid && lastNamed && named
        if (!isNamedSwitch) {
            // Same uid, first launch, guest→named, or named→anonymous sign-out:
            // keep the library, just advance identity flags.
            prefsStore.edit()
                .putString(KEY_LAST_UID, uid)
                .putBoolean(KEY_LAST_NAMED, named)
                .apply()
            return true
        }

        // Non-empty library requires confirmation + a pre-wipe snapshot.
        // A read failure is treated as non-empty so we snapshot/confirm rather
        // than wiping blind. Cancellation rethrows via the helper.
        val nonEmpty = suspendRunCatching { backupManager.hasLocalLibraryData() }
            .getOrElse { throwable ->
                logW("hasLocalLibraryData failed, assuming non-empty (switch ${uid.take(4)}…): ${throwable.message}")
                true
            }
        if (nonEmpty) {
            val gate = onAccountSwitchRequiresConfirmation
            if (gate != null) {
                val confirmed = suspendRunCatching { gate() }.getOrElse { throwable ->
                    logW("account-switch confirmation threw, aborting wipe (switch ${uid.take(4)}…): ${throwable.message}")
                    telemetry.crashlyticsLog("account_switch_confirm_failed error=${throwable.javaClass.simpleName}")
                    false
                }
                if (!confirmed) {
                    logW("account-switch wipe declined by user, flags unchanged (switch ${uid.take(4)}…)")
                    return false
                }
            }
            val snapResult = suspendRunCatching { backupManager.exportAccountSwitchSnapshot() }
            if (snapResult.getOrNull() == null) {
                val reason = snapResult.exceptionOrNull()?.message ?: "snapshot returned null"
                logW("pre-wipe snapshot failed, aborting wipe, flags unchanged: $reason")
                telemetry.crashlyticsLog("account_switch_snapshot_failed error=$reason")
                return false
            }
        }

        val wipe = suspendRunCatching {
            favoriteDao.clearAll()
            historyDao.clearAll()
            readerAnnotationDao.clearAll()
            readChapterDao.clearAll()
            progressDao.clearAll()
            // U-09 extended wipe (user-curated local state). These stores
            // have no bulk-clear API, so each is cleared per-id over its
            // snapshot — see comments for the two shapes that cannot be
            // cleared at all from this coordinator.
            collectionManager.snapshot().forEach { col ->
                collectionManager.deleteCollection(col.id)
            }
            bookmarkManager.snapshot().forEach { (mangaId, items) ->
                items.forEach { bookmarkManager.removeBookmark(mangaId, it.id) }
            }
            achievementManager.goals.first().forEach { goal ->
                achievementManager.deleteGoal(goal.id)
            }
            // ReadingStatsStore: no bulk-clear/reset API for totals — restore()
            // merges with max(), so totals survive the switch. Best effort is
            // pruning old daily buckets (keep today only). The totals remain
            // as accepted residue until a reset API lands in
            // ReadingStatsStore.kt (outside this change's file list).
            readingStatsStore.pruneOldStats(keepDays = 0)
            // AchievementManager: no bulk-clear for unlocks/totals — only the
            // per-goal delete above is possible via public APIs. Unlocks and
            // page/chapter totals survive, and the next throttled Firestore
            // sync can re-upload them under the new uid's user_achievements
            // doc. Same follow-up: a reset API in AchievementManager.kt.
            prefs.clearSyncTombstones()
        }
        if (wipe.isFailure) {
            val failure = wipe.exceptionOrNull()
            logW(
                "account-switch wipe failed, flags unchanged, retry next launch " +
                    "(switch ${uid.take(4)}…, error=${failure?.javaClass?.simpleName}: ${failure?.message})",
                failure
            )
            telemetry.crashlyticsLog(
                "account_switch_wipe_failed error=${failure?.javaClass?.simpleName}"
            )
            return false
        }

        prefsStore.edit()
            .putString(KEY_LAST_UID, uid)
            .putBoolean(KEY_LAST_NAMED, named)
            .apply()
        return true
    }

    /**
     * Loud log that never breaks control flow: android.util.Log throws
     * "not mocked" under plain JVM unit tests, so failure logging must not
     * mask the retry/abort decision (ReadingStatsStore precedent). On
     * device this is a normal Log.w; telemetry.crashlyticsLog provides the
     * Crashlytics breadcrumb alongside.
     */
    private fun logW(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        }
    }

    private companion object {
        const val TAG = "AccountSwitch"
        const val KEY_LAST_UID = "last_sync_uid"
        const val KEY_LAST_NAMED = "last_sync_named"
    }
}
