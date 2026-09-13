package com.exapps.mangaworld.core.firebase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic inactivity-reminder check (24h).
 *
 * [NotificationPolicyManager.checkAndSendReminders] used to run only from
 * [FirebaseStartupCoordinator] at cold start — but the 5-minute
 * just-opened suppression (keyed on `app_start_time`, written in
 * `Application.onCreate`) tripped on every one of those calls, so reminders
 * could never fire. A background worker has a stale `app_start_time` and
 * lets the check actually run; the 24h throttle inside the manager still
 * bounds frequency. No network constraint: the check is a local DB read.
 */
@HiltWorker
class ReminderCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notificationPolicyManager: NotificationPolicyManager
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            notificationPolicyManager.checkAndSendReminders()
            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
