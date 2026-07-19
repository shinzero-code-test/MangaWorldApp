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
        crashlytics.log("scraper_failure source=$sourceId stage=$stage message=${throwable.message}")
        crashlytics.setCustomKey("scraper_source", sourceId)
        crashlytics.setCustomKey("scraper_stage", stage)
        crashlytics.recordException(throwable)
    }

    fun setActiveSource(sourceId: String) {
        crashlytics.setCustomKey("active_source", sourceId)
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
