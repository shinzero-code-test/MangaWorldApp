package com.exapps.mangaworld.core.source.sync

import android.content.Context
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
 */
@Singleton
class PluginSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
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
    }
}
