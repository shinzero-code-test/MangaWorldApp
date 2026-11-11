package com.exapps.mangaworld.core.firebase.work

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-18e: the retry policy for the durable community writes.
 *
 * Split out of [CommunityWriteWorker] on purpose: the worker is an Android
 * class (`CoroutineWorker` needs a `WorkerParameters` and cannot run on the
 * JVM), while the policy is pure Kotlin over two interfaces — so the retry /
 * terminal-failure contract is unit-testable.
 */
@Singleton
class CommunityWriteDrainer @Inject constructor(
    private val outbox: CommunityWriteOutbox,
    private val executor: CommunityWriteExecutor
) {

    /**
     * Delivers every pending write.
     *
     * - delivered: the write landed → the row is dropped.
     * - retryable: the write may still succeed → the row stays, the attempt
     *   counter is bumped and the caller asks WorkManager for a retry.
     * - failed: the write can never succeed → the row is dropped AND the
     *   failure is recorded (never silently swallowed).
     */
    suspend fun drain(): DrainResult {
        val pending = outbox.pending()
        if (pending.isEmpty()) return DrainResult()

        var delivered = 0
        var retryable = 0
        val failures = mutableListOf<CommunityWriteFailure>()

        pending.forEach { write ->
            try {
                executor.execute(write)
                outbox.remove(write.id)
                delivered++
            } catch (cancellation: CancellationException) {
                // The worker was cancelled: the row stays, the next run picks it up.
                throw cancellation
            } catch (t: Throwable) {
                when (classifyCommunityWriteFailure(t)) {
                    CommunityWriteFailureKind.TERMINAL -> {
                        outbox.recordFailure(write.id, describeCommunityWriteFailure(t))
                        failures += CommunityWriteFailure(
                            id = write.id,
                            kind = write.kind,
                            error = describeCommunityWriteFailure(t),
                            at = System.currentTimeMillis()
                        )
                    }
                    CommunityWriteFailureKind.RETRYABLE -> {
                        val attempts = outbox.recordRetry(write.id, describeCommunityWriteFailure(t))
                        if (attempts >= MAX_ATTEMPTS) {
                            // The retry budget is spent: record it instead of
                            // looping forever on a write that never lands.
                            outbox.recordFailure(write.id, describeCommunityWriteFailure(t))
                            failures += CommunityWriteFailure(
                                id = write.id,
                                kind = write.kind,
                                error = describeCommunityWriteFailure(t),
                                at = System.currentTimeMillis()
                            )
                        } else {
                            retryable++
                        }
                    }
                }
            }
        }

        return DrainResult(
            delivered = delivered,
            retryable = retryable,
            failed = failures.size,
            failures = failures
        )
    }

    companion object {
        /**
         * How often one write may be re-driven before it is recorded as a
         * terminal failure. WorkManager's exponential backoff spaces the
         * attempts out; this bounds the total.
         */
        const val MAX_ATTEMPTS = 5
    }
}

/** Outcome of one [CommunityWriteDrainer.drain] run. */
data class DrainResult(
    /** Writes that landed. */
    val delivered: Int = 0,
    /** Writes that failed but may still succeed (the caller should retry). */
    val retryable: Int = 0,
    /** Writes that can never succeed (recorded, never silently swallowed). */
    val failed: Int = 0,
    /** The terminal failures, for logging/diagnostics. */
    val failures: List<CommunityWriteFailure> = emptyList()
) {
    /** True when the worker must ask WorkManager for another attempt. */
    val shouldRetry: Boolean get() = retryable > 0
}
