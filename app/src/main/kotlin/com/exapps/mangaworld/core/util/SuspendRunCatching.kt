package com.exapps.mangaworld.core.util

import kotlinx.coroutines.CancellationException

/**
 * Cancellation-aware [runCatching] (U-13).
 *
 * `runCatching` swallows [CancellationException], so a cancelled load renders
 * as a user-visible error, work continues after the user navigates away, and
 * post-cancel state writes + widget refreshes still execute (`ScriptRunner` /
 * `ScriptBridge` already rethrow correctly — this is the shared form).
 *
 * Shape mirrors [runCatching] exactly so call-site migration is mechanical:
 * `= runCatching { … }` → `= suspendRunCatching { … }`.
 */
inline fun <T> suspendRunCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
