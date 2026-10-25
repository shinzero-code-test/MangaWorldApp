package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3.5 Gate 0 — observable fleet telemetry for the plugin system.
 *
 * Sync outcomes, resume results, reconciler revivals, health transitions, and
 * script executions previously existed only as logcat lines: unobservable on
 * real devices, which made the Phase 4 stability gate undecidable. This port
 * carries the same signals to Crashlytics/Analytics in aggregate form.
 *
 * Privacy: ids, versions, engines, outcome names, and counts only. Never URLs,
 * hosts beyond the allow-listed set, bodies, cookies, or messages.
 * Production uses [FirebasePluginTelemetry]; tests record with a fake.
 */
data class PluginSyncReport(
    val indexNotModified: Boolean,
    /** Outcome simple names → count (Updated, HeldForConsent, Failed, …). */
    val outcomeCounts: Map<String, Int>,
    /** Ids that failed transport/validation (bounded by the caller). */
    val failedIds: List<String>,
    /** Ids rejected deterministically (bounded by the caller). */
    val rejectedIds: List<String>,
    val revocations: List<String>,
    val evictedVersions: Int,
    val durationMs: Long
)

interface PluginTelemetry {
    fun logSync(report: PluginSyncReport)
    fun logBootstrap(
        activatedIds: List<String>,
        skippedByReason: Map<String, Int>,
        reconciled: Int,
        appVersion: String
    )

    fun logHealth(id: String, from: HealthState, to: HealthState)
    fun logScriptCall(
        id: String,
        entry: String,
        success: Boolean,
        durationMs: Long,
        failureClass: String?,
        /** Payload version — Gate 0 query 3 is `script_call × entry × version`. */
        version: Int
    )

    /**
     * A downloaded manifest failed trust/compat verification.
     *
     * Gate 0 query 4 (`trust_reject × reason`) had NO emitter at all before this:
     * the only record was a Crashlytics breadcrumb on the rejection path. A
     * non-zero rate in steady state means the distribution or the anchor calendar
     * is wrong, so it has to be queryable, not just visible in a crash report.
     */
    fun logTrustReject(id: String, reason: String)
}

/**
 * Crashlytics-backed implementation. Routine events are log-lines + a few
 * custom keys (no `recordException` — alert fatigue); unexpected failures keep
 * their exception via the existing `logScraperFailure` path at call sites.
 */
@Singleton
class FirebasePluginTelemetry @Inject constructor(
    private val telemetry: FirebaseTelemetry,
    private val analytics: com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
) : PluginTelemetry {

    override fun logSync(report: PluginSyncReport) {
        val counts = report.outcomeCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        telemetry.crashlyticsLog(
            "plugin_sync notModified=${report.indexNotModified} outcomes={$counts} " +
                "revoked=${report.revocations.size} evicted=${report.evictedVersions} " +
                "tookMs=${report.durationMs}"
        )
        telemetry.setTelemetryKey("plugin_sync_failed", report.failedIds.take(10).joinToString(","))
        telemetry.setTelemetryKey("plugin_sync_rejected", report.rejectedIds.take(10).joinToString(","))
        telemetry.setTelemetryKey("plugin_sync_revoked", report.revocations.take(10).joinToString(","))
        // Gate 0 query 1 is `sweep_outcome x outcome`, so emit one event per
        // outcome rather than a packed string: a string needs parsing at query
        // time and cannot be grouped by GA4/BigQuery.
        report.outcomeCounts.forEach { (outcome, count) ->
            if (count <= 0) return@forEach
            analytics.logEvent(
                name = "plugin_sync_outcome",
                params = mapOf("outcome" to outcome.take(40), "count" to count.toLong())
            )
        }
        analytics.logEvent(
            name = "plugin_sweep",
            params = mapOf(
                "not_modified" to if (report.indexNotModified) 1L else 0L,
                "revoked" to report.revocations.size.toLong(),
                "evicted" to report.evictedVersions.toLong(),
                "duration_ms" to report.durationMs.coerceAtLeast(0L)
            )
        )
    }

    override fun logBootstrap(
        activatedIds: List<String>,
        skippedByReason: Map<String, Int>,
        reconciled: Int,
        appVersion: String
    ) {
        val skipped = skippedByReason.entries.joinToString(",") { "${it.key}=${it.value}" }
        telemetry.crashlyticsLog(
            "plugin_bootstrap app=$appVersion activated=${activatedIds.size} " +
                "activatedIds=${activatedIds.take(10).joinToString(",")} " +
                "skipped={$skipped} reconciled=$reconciled"
        )
        telemetry.setTelemetryKey("plugin_bootstrap_revived", reconciled.toString())
        analytics.logEvent(
            name = "plugin_bootstrap",
            params = mapOf(
                "activated" to activatedIds.size.toLong(),
                "skipped" to skippedByReason.values.sum().toLong(),
                "reconciled" to reconciled.toLong()
            )
        )
    }

    override fun logHealth(id: String, from: HealthState, to: HealthState) {
        telemetry.crashlyticsLog("plugin_health id=$id $from->$to")
        telemetry.setTelemetryKey("plugin_health_last", "$id:$to")
        analytics.logEvent(
            name = "plugin_health",
            params = mapOf("plugin_id" to id, "from" to from.name, "to" to to.name)
        )
    }

    override fun logScriptCall(
        id: String,
        entry: String,
        success: Boolean,
        durationMs: Long,
        failureClass: String?,
        version: Int
    ) {
        // Per-call volume: failures always log; successes log (duration sample
        // for the perf budget) — both are single lines, no exceptions.
        telemetry.crashlyticsLog(
            "script_call id=$id entry=$entry v=$version success=$success " +
                "tookMs=$durationMs failure=${failureClass ?: "-"}"
        )
        analytics.logEvent(
            name = "plugin_script_call",
            params = mapOf(
                "plugin_id" to id,
                "entry" to entry.take(24),
                "version" to version.toLong(),
                "success" to if (success) 1L else 0L,
                "duration_ms" to durationMs.coerceAtLeast(0L),
                "failure" to (failureClass ?: "-").take(40)
            )
        )
    }

    override fun logTrustReject(id: String, reason: String) {
        telemetry.crashlyticsLog("plugin_trust_reject id=$id reason=$reason")
        analytics.logEvent(
            name = "plugin_trust_reject",
            params = mapOf("plugin_id" to id, "reason" to reason.take(40))
        )
    }
}
