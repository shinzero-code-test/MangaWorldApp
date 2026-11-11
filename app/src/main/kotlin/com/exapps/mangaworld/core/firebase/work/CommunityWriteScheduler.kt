package com.exapps.mangaworld.core.firebase.work

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-18e: schedules the recovery worker for the durable community writes.
 *
 * Behind an interface so the repository can enqueue a recovery without
 * touching WorkManager directly (and so the unit suite can assert that the
 * recovery was scheduled).
 */
interface CommunityWriteScheduler {
    /** Makes sure a recovery run is pending. Safe to call repeatedly. */
    fun schedule()
}

/**
 * Production [CommunityWriteScheduler].
 *
 * The work is scheduled with a delay: the foreground attempt owns the normal
 * outcome, and the delayed run is the safety net for the case the foreground
 * cannot handle — the process died mid-write, so no attempt ever reported
 * back. The delay (longer than the foreground's 30s write deadline) keeps the
 * two from racing on the same row.
 *
 * `KEEP` coalesces a burst of writes into ONE pending run, and the run drains
 * the whole outbox.
 */
@Singleton
class WorkManagerCommunityWriteScheduler @Inject constructor(
    private val workManager: WorkManager
) : CommunityWriteScheduler {

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<CommunityWriteWorker>()
            .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        /** Unique name of the outbox drain work. */
        const val WORK_NAME = "community_write_outbox"

        /**
         * Longer than the foreground write deadline (30s), so the recovery run
         * never races the attempt it is meant to rescue.
         */
        const val INITIAL_DELAY_SECONDS = 60L

        /** WorkManager's exponential backoff floor for the drain work. */
        const val MIN_BACKOFF_SECONDS = 30L
    }
}
