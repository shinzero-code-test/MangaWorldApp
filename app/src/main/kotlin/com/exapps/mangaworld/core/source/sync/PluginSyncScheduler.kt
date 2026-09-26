package com.exapps.mangaworld.core.source.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2B sync schedule: ETag-conditional index poll every 24h on unmetered
 * power-friendly constraints, plus an on-demand lane (Sources settings "check
 * for updates" hook, post-restore re-sync).
 *
 * v9.1.1 bootstrap fix: the periodic worker alone never fires promptly on
 * fresh installs (first run is at the OS's discretion), and the on-demand lane
 * had no UI trigger — so new sources could sit undistributed for days with no
 * user action able to hurry it. `requestNowIfStale()` closes both gaps: app
 * start triggers a one-shot sync when no sweep completed recently.
 */
@Singleton
class PluginSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Last completed sweep (success OR deterministic failure — both checked). */
    fun lastSyncMs(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun recordSyncCompleted(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC, nowMs).apply()
    }

    /**
     * One-shot sync when the last completed sweep is older than [staleAfterMs].
     * Returns true when a sync was enqueued. Pure staleness predicate is
     * [isStale] (unit-tested; prefs I/O stays here).
     */
    suspend fun requestNowIfStale(staleAfterMs: Long = STALE_AFTER_MS): Boolean {
        if (!isStale(lastSyncMs(), System.currentTimeMillis(), staleAfterMs)) return false
        requestNow()
        return true
    }
    suspend fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PluginSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
            .await()
    }

    /** On-demand sync (never replaces the periodic schedule). */
    suspend fun requestNow() {
        val request = OneTimeWorkRequestBuilder<PluginSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag(TAG_ONESHOT)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG_ONESHOT, ExistingWorkPolicy.REPLACE, request)
            .await()
    }

    companion object {
        private const val TAG = "plugin_sync_periodic"
        private const val TAG_ONESHOT = "plugin_sync_once"

        const val PREFS_FILE = "plugin_sync"
        private const val KEY_LAST_SYNC = "last_sync_ms"

        /** Stale when no completed sweep within the window (never counts as fresh). */
        const val STALE_AFTER_MS = 12L * 60 * 60 * 1000

        internal fun isStale(lastSyncMs: Long, nowMs: Long, staleAfterMs: Long): Boolean {
            if (lastSyncMs <= 0L) return true
            return nowMs - lastSyncMs >= staleAfterMs
        }
    }
}
