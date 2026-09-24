package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import com.google.firebase.perf.FirebasePerformance
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds retry logic, configurable timeouts, and scraper metadata to OkHttp requests.
 *
 * NOTE: HTTP metrics (latency, payload size, status code) are automatically
 * captured by the Firebase Performance Gradle plugin's auto-instrumentation.
 * This interceptor only adds custom attributes — it does NOT create its own
 * HttpMetric to avoid double-counting.
 */
@Singleton
class FirebaseNetworkInterceptor @Inject constructor(
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val firebaseTelemetry: FirebaseTelemetry,
    /** Provider (not direct): the registry's scrapers need the OkHttp client this interceptor belongs to. */
    private val registryProvider: javax.inject.Provider<com.exapps.mangaworld.core.source.plugins.SourceRegistry>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val runtimeConfig = remoteConfigManager.currentScraperRuntimeConfig()
        val request = chain.request()
        val host = request.url.host.lowercase(Locale.US)
        val sourceId = runCatching { registryProvider.get().all() }.getOrDefault(emptyList())
            .firstOrNull { plugin ->
            // Effective host first (Remote Config override), then the descriptor
            // default so traffic is still attributed right after a domain move.
            val id = plugin.descriptor.id.value
            val resolved = HostPolicy.hostOf(SourceDomainOverrides.baseUrlFor(id, plugin.descriptor.baseUrl))
            val bundled = HostPolicy.hostOf(plugin.descriptor.baseUrl)
            host == resolved || host.endsWith(".$resolved") ||
                host == bundled || host.endsWith(".$bundled")
        }?.descriptor?.id?.value

        val tunedChain = chain
            .withConnectTimeout(runtimeConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
            .withReadTimeout(runtimeConfig.readTimeoutSeconds, TimeUnit.SECONDS)
            .withWriteTimeout(runtimeConfig.writeTimeoutSeconds, TimeUnit.SECONDS)

        // Use a non-HTTP custom trace for scraper metadata only.
        // HTTP latency/payload metrics are handled by Gradle auto-instrumentation.
        val trace = FirebasePerformance.getInstance().newTrace("scraper_request")
        trace.putAttribute("network_type", firebaseTelemetry.refreshNetworkTypeKey())
        sourceId?.let {
            // Per-trace attribution only: the global `active_source` Crashlytics
            // key was racy under parallel paging across 18 sources (M-review).
            trace.putAttribute("scraper_source", it)
        }
        trace.start()

        try {
            var lastException: IOException? = null
            repeat(runtimeConfig.retryCount + 1) { attempt ->
                try {
                    val response = tunedChain.proceed(request)
                    trace.putAttribute("response_code", response.code.toString())
                    return response
                } catch (exception: IOException) {
                    lastException = exception
                    trace.incrementMetric("retry_count", 1)
                    if (attempt == runtimeConfig.retryCount) throw exception
                }
            }

            throw lastException ?: IOException("Unknown scraper network error")
        } finally {
            trace.stop()
        }
    }
}
