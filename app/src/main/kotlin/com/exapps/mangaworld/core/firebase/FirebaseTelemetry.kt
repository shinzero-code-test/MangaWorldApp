package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val performance = FirebasePerformance.getInstance()

    /** Call after auth state changes to attach user context to crash reports. */
    fun setCrashlyticsUserId(uid: String?) {
        crashlytics.setUserId(uid ?: "")
    }

    fun logScraperFailure(sourceId: String, stage: String, throwable: Throwable) {
        setActiveSource(sourceId)
        refreshNetworkTypeKey()
        crashlytics.log("scraper_failure source=$sourceId stage=$stage error=${throwable.javaClass.simpleName}")
        crashlytics.setCustomKey("scraper_source", sourceId)
        crashlytics.setCustomKey("scraper_stage", stage)
        crashlytics.recordException(throwable)
    }

    /**
     * Non-fatal for community read-path failures: starved listeners and
     * failed rescue fetches. The network type is the key diagnostic — a
     * stalled Watch stream on vpn/other with working unary writes reads
     * exactly like "saved in Firestore but invisible in the app".
     */
    fun logListenerStarvation(surface: String, detail: String, throwable: Throwable) {
        val network = refreshNetworkTypeKey()
        crashlytics.log("listener_starvation surface=$surface detail=$detail network=$network error=${throwable.javaClass.simpleName}: ${throwable.message}")
        crashlytics.setCustomKey("starvation_surface", surface)
        crashlytics.setCustomKey("starvation_detail", detail)
        crashlytics.recordException(throwable)
    }

    /**
     * Denial counter per surface: PERMISSION_DENIED on a public surface is
     * usually routine (private profile / hidden section), so it must NOT be a
     * non-fatal — but swallowing it entirely made "query denied" invisible
     * next to "query empty" and "data absent". This logs the code + surface
     * (no PII, no exception) so denial spikes are distinguishable in
     * Crashlytics logs without alert fatigue.
     */
    fun logListenerDenial(surface: String, code: String) {
        refreshNetworkTypeKey()
        crashlytics.log("listener_denial surface=$surface code=$code")
        crashlytics.setCustomKey("denial_surface", surface)
        crashlytics.setCustomKey("denial_code", code)
    }

    /**
     * Mapper-drop counter: when a snapshot arrives but tolerant mappers drop
     * rows (strict field reads), the screen looks "empty despite data".
     * Counts only — no document content ever leaves the device.
     */
    fun logMapperDrops(surface: String, received: Int, mapped: Int) {        if (received <= mapped) return
        refreshNetworkTypeKey()
        val dropped = received - mapped
        crashlytics.log("mapper_drops surface=$surface received=$received mapped=$mapped dropped=$dropped")
        crashlytics.setCustomKey("mapper_surface", surface)
        crashlytics.setCustomKey("mapper_dropped", dropped)
        crashlytics.recordException(IllegalStateException("mapper dropped $dropped/$received docs on $surface"))
    }

    fun setActiveSource(sourceId: String) {
        crashlytics.setCustomKey("active_source", sourceId)
    }

    /**
     * Low-volume breadcrumb for subsystem telemetry ports (plugin sync, health,
     * scripts). Log-line only — no exception recorded, so routine fleet
     * chatter never becomes alert fatigue. See `PluginTelemetry` for the
     * privacy contract (ids/counts only, never content).
     */
    fun crashlyticsLog(message: String) {
        crashlytics.log(message)
    }

    /** Bounded custom key for telemetry ports (callers truncate value lists). */
    fun setTelemetryKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }

    fun currentNetworkType(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = connectivityManager.activeNetwork ?: return "offline"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    fun refreshNetworkTypeKey(): String = currentNetworkType().also {
        crashlytics.setCustomKey("network_type", it)
    }

    fun <T> trace(name: String, block: () -> T): T {
        val trace = performance.newTrace(name)
        trace.putAttribute("network_type", currentNetworkType())
        trace.start()
        return try {
            block()
        } finally {
            trace.stop()
        }
    }

    suspend fun <T> traceSuspend(name: String, block: suspend () -> T): T {
        val trace = performance.newTrace(name)
        trace.putAttribute("network_type", currentNetworkType())
        trace.start()
        return try {
            block()
        } finally {
            trace.stop()
        }
    }

    suspend fun <T> traceDatabaseSync(
        operation: String,
        metrics: Map<String, Long> = emptyMap(),
        block: suspend () -> T
    ): T {
        val trace = performance.newTrace("db_sync_$operation")
        trace.putAttribute("network_type", currentNetworkType())
        metrics.forEach { (key, value) -> trace.putMetric(key, value) }
        trace.start()
        return try {
            block()
        } finally {
            trace.stop()
        }
    }
}
