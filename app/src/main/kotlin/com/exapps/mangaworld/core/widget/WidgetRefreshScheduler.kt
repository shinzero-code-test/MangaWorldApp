package com.exapps.mangaworld.core.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        // Battery guard mirrors the app's Firebase sync: a 6h full-fan-out
        // refresh defers on low battery.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RemoteWidgetRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()
        )

        WorkManager.getInstance(context).enqueueUniqueWork(
            BOOTSTRAP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RemoteWidgetRefreshWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()
        )
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "remote_widget_refresh_periodic"
        const val BOOTSTRAP_WORK_NAME = "remote_widget_refresh_bootstrap"
    }
}
