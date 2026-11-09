package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import com.google.firebase.perf.FirebasePerformance
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
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
            // U-18: bounded exponential backoff + Retry-After; cancellation
            // must propagate (caller cancellation kills the retry loop).
            for (attempt in 0..runtimeConfig.retryCount) {
                try {
                    val response = tunedChain.proceed(request)
                    trace.putAttribute("response_code", response.code.toString())
                    // Honor Retry-After on 429/503 when we will retry.
                    if ((response.code == 429 || response.code == 503) && attempt < runtimeConfig.retryCount) {
                        val retryAfterMs = parseRetryAfter(response)
                        response.close()
                        lastException = IOException("HTTP ${response.code} (retrying)")
                        trace.incrementMetric("retry_count", 1)
                        sleepBackoff(attempt, retryAfterMs)
                        continue
                    }
                    return response
                } catch (e: InterruptedIOException) {
                    // U-18: cancellation passthrough — never retry a cancelled call.
                    throw e
                } catch (exception: IOException) {
                    // OkHttp surfaces coroutine cancellation as InterruptedIOException
                    // (handled above). Any other IOException is retryable unless the
                    // thread was interrupted.
                    if (Thread.currentThread().isInterrupted) throw exception
                    lastException = exception
                    trace.incrementMetric("retry_count", 1)
                    if (attempt == runtimeConfig.retryCount) throw exception
                    sleepBackoff(attempt, null)
                }
            }

            throw lastException ?: IOException("Unknown scraper network error")
        } finally {
            trace.stop()
        }
    }

    private fun parseRetryAfter(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        // Delta seconds form (preferred); HTTP-date form is ignored (coarse).
        val secs = raw.toLongOrNull() ?: return null
        // Clamp to [0, 30]s so a hostile far-future date cannot pin the queue.
        return (secs.coerceIn(0L, 30L)) * 1000L
    }

    private fun sleepBackoff(attempt: Int, retryAfterMs: Long?) {
        val backoff = when {
            retryAfterMs != null -> retryAfterMs
            else -> (200L shl attempt).coerceAtMost(2000L) // 200,400,800… capped 2s
        }
        if (backoff <= 0) return
        // Sleep respects interruption (cancellation): an interrupted sleep
        // aborts the retry loop on the next proceed() or here.
        try {
            Thread.sleep(backoff)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted during retry backoff", e)
        }
    }
}
