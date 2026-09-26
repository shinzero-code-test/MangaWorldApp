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
        failureClass: String?
    )
}

/**
 * Crashlytics-backed implementation. Routine events are log-lines + a few
 * custom keys (no `recordException` — alert fatigue); unexpected failures keep
 * their exception via the existing `logScraperFailure` path at call sites.
 */
@Singleton
class FirebasePluginTelemetry @Inject constructor(
    private val telemetry: FirebaseTelemetry
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
    }

    override fun logHealth(id: String, from: HealthState, to: HealthState) {
        telemetry.crashlyticsLog("plugin_health id=$id $from->$to")
        telemetry.setTelemetryKey("plugin_health_last", "$id:$to")
    }

    override fun logScriptCall(
        id: String,
        entry: String,
        success: Boolean,
        durationMs: Long,
        failureClass: String?
    ) {
        // Per-call volume: failures always log; successes log (duration sample
        // for the perf budget) — both are single lines, no exceptions.
        telemetry.crashlyticsLog(
            "script_call id=$id entry=$entry success=$success " +
                "tookMs=$durationMs failure=${failureClass ?: "-"}"
        )
    }
}
