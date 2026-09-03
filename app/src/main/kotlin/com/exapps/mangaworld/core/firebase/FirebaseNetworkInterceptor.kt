package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.effectiveHost
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
    private val firebaseTelemetry: FirebaseTelemetry
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val runtimeConfig = remoteConfigManager.currentScraperRuntimeConfig()
        val request = chain.request()
        val host = request.url.host.lowercase(Locale.US)
        val sourceId = MangaSource.entries.firstOrNull { source ->
            // Effective host first (Remote Config override), then the enum
            // default so traffic is still attributed right after a domain move.
            val resolved = source.effectiveHost()
            val bundled = source.baseUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase(Locale.US)
            host == resolved || host.endsWith(".$resolved") ||
                host == bundled || host.endsWith(".$bundled")
        }?.id

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
