package com.exapps.mangaworld.core.util

import kotlinx.coroutines.CancellationException

/**
 * U-24: the ONE best-effort envelope for the "this may fail — log it and keep
 * going" probes that the plugin distribution path repeats everywhere.
 *
 * Before this helper the same shape was hand-written at ~15 call sites
 * (`PluginSyncEngine`, `BundledPluginLoader`, `SourceHealthMonitor`):
 *
 * ```kotlin
 * runCatching { settingsRepo.toggleSource(id, false) }        // silent
 * runCatching { resumeInstalled(record) }.getOrElse { e ->     // logged
 *     log("resume ${record.id} failed closed: ${e.message}")
 *     Outcome.Skipped(record.id, "exception")
 * }
 * ```
 *
 * Two defects came with that duplication:
 * 1. **U-13** — bare [runCatching] swallows [CancellationException]. A sweep
 *    cancelled by a screen exit rendered as a failed probe, and the
 *    post-cancel rollback writes kept running (zombie writes after
 *    navigation).
 * 2. **Silent failures** — the "fire and forget" variants discarded the
 *    failure entirely, so a rollback that never happened looked exactly like
 *    one that did.
 *
 * [guarded] fixes both in one place:
 * - cancellation is RE-THROWN (never converted into a failed probe),
 * - every failure is logged through the caller's own sink with the exception
 *   class + message (the house log format),
 * - the result is a plain [Result], so callers keep their existing
 *   `getOrElse` / `getOrDefault` fallbacks.
 *
 * The log sink is a parameter (not a global) because each owner already has
 * its own test-replaceable sink: `PluginSyncEngine.log`,
 * `BundledPluginLoader.log`, `SourceHealthMonitor.log`. Raw `android.util.Log`
 * throws on plain JVM, so production code must never call it directly — every
 * owner in this package injects/overrides its sink for tests.
 *
 * Pure Kotlin + coroutines only: no Android dependencies (JVM-CI-safe).
 */
suspend fun <T> guarded(
    stage: String,
    log: (String) -> Unit,
    block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        // U-13: a cancelled sweep is not a failed probe. Rethrow so the
        // cancellation reaches the caller (and the sync abort handler) instead
        // of being logged as an error and continuing with zombie writes.
        throw e
    } catch (t: Throwable) {
        // The sink is best-effort TOO: a broken logger must not turn a
        // handled failure into a crash. (Raw `android.util.Log` throws
        // "Stub!" on the JVM, so an unreplaced default sink would otherwise
        // replace the original failure with an unrelated exception.)
        try {
            log("$stage failed: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
        } catch (_: Throwable) {
            // Swallowed on purpose — see above.
        }
        Result.failure(t)
    }
}
