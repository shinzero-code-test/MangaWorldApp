package com.exapps.mangaworld.core.firebase.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * U-18e: drains the durable community-write outbox.
 *
 * The worker is a thin shell around [CommunityWriteDrainer]: the retry policy
 * lives in the drainer so it can be unit-tested on the JVM (a `CoroutineWorker`
 * needs a `WorkerParameters` and can only run on a device).
 *
 * Result mapping:
 * - a retryable failure → [Result.retry] (WorkManager re-runs it with
 *   exponential backoff);
 * - a terminal failure → [Result.failure] — the failure was recorded in the
 *   outbox AND the work itself is marked failed, so it is never silently
 *   swallowed;
 * - everything delivered → [Result.success].
 */
@HiltWorker
class CommunityWriteWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val drainer: CommunityWriteDrainer
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = drainer.drain()
        result.failures.forEach { failure ->
            android.util.Log.w(
                "CommunityWriteWorker",
                "write ${failure.id} failed permanently: ${failure.error}"
            )
        }
        return when {
            result.shouldRetry -> Result.retry()
            result.failed > 0 -> Result.failure()
            else -> Result.success()
        }
    }
}
