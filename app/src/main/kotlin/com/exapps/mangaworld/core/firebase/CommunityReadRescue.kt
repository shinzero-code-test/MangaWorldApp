package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.util.suspendRunCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watchdog for Firestore/RTDB snapshot listeners.
 *
 * A listener that neither delivers nor errors (stalled Watch stream, wedged
 * SDK state) starves every `combine()` downstream: screens pin at their
 * initial state (blank lists, dead tabs, fallback profile) with zero signal.
 * Writes are unaffected (unary RPCs), which reads exactly like "saved in
 * Firestore but invisible in the app".
 *
 * [withStarvationFallback] races the live listener against a one-shot `get()`:
 * whichever delivers first wins; the loser is ignored. Healthy devices pay
 * nothing (the watchdog is cancelled on first emission). [onFallbackError]
 * fires only when the rescue fetch itself fails, so callers can report
 * unexpected failures to Crashlytics while ignoring routine offline stalls.
 *
 * Pure flow logic — fully unit-testable, no Firebase needed.
 */
internal fun <T> Flow<T>.withStarvationFallback(
    timeoutMs: Long = LISTENER_STARVATION_TIMEOUT_MS,
    fallback: suspend () -> T,
    onFallbackError: (Throwable) -> Unit = {}
): Flow<T> = channelFlow {
    val settled = AtomicBoolean(false)
    launch {
        delay(timeoutMs)
        if (settled.compareAndSet(false, true)) {
            // U-13: cancellation-aware — a cancelled rescue fetch must not be
            // reported as a starvation failure (the listener may simply have
            // been torn down because the collector went away).
            suspendRunCatching { fallback() }
                .onSuccess { trySend(it) }
                .onFailure { onFallbackError(it) }
        }
    }
    this@withStarvationFallback.collect {
        settled.set(true)
        send(it)
    }
}

/** Long enough to never race a healthy listener, short enough to feel instant. */
internal const val LISTENER_STARVATION_TIMEOUT_MS = 8_000L
