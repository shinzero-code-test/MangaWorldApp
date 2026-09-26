package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HealthState
import com.exapps.mangaworld.core.source.plugins.PluginSyncReport
import com.exapps.mangaworld.core.source.plugins.PluginTelemetry

/**
 * Gate 0 test fake: records every fleet-telemetry event in memory so tests can
 * assert WHAT would be observable in production (event shapes, counts, state
 * transitions) without Firebase on JVM.
 */
class RecordingPluginTelemetry : PluginTelemetry {
    data class Bootstrap(
        val activatedIds: List<String>,
        val skippedByReason: Map<String, Int>,
        val reconciled: Int,
        val appVersion: String
    )

    data class Health(val id: String, val from: HealthState, val to: HealthState)

    data class ScriptCall(
        val id: String,
        val entry: String,
        val success: Boolean,
        val durationMs: Long,
        val failureClass: String?
    )

    val syncs = mutableListOf<PluginSyncReport>()
    val bootstraps = mutableListOf<Bootstrap>()
    val healths = mutableListOf<Health>()
    val scriptCalls = mutableListOf<ScriptCall>()

    override fun logSync(report: PluginSyncReport) {
        syncs += report
    }

    override fun logBootstrap(
        activatedIds: List<String>,
        skippedByReason: Map<String, Int>,
        reconciled: Int,
        appVersion: String
    ) {
        bootstraps += Bootstrap(activatedIds, skippedByReason, reconciled, appVersion)
    }

    override fun logHealth(id: String, from: HealthState, to: HealthState) {
        healths += Health(id, from, to)
    }

    override fun logScriptCall(
        id: String,
        entry: String,
        success: Boolean,
        durationMs: Long,
        failureClass: String?
    ) {
        scriptCalls += ScriptCall(id, entry, success, durationMs, failureClass)
    }

    fun clear() {
        syncs.clear()
        bootstraps.clear()
        healths.clear()
        scriptCalls.clear()
    }
}
