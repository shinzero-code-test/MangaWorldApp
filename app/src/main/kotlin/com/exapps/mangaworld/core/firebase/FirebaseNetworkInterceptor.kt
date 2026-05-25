package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.domain.model.MangaSource
import com.google.firebase.perf.FirebasePerformance
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
            val sourceHost = source.baseUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase(Locale.US)
            host == sourceHost || host.endsWith(".$sourceHost")
        }?.id

        val tunedChain = chain
            .withConnectTimeout(runtimeConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
            .withReadTimeout(runtimeConfig.readTimeoutSeconds, TimeUnit.SECONDS)
            .withWriteTimeout(runtimeConfig.writeTimeoutSeconds, TimeUnit.SECONDS)

        val metric = FirebasePerformance.getInstance().newHttpMetric(request.url.toString(), request.method)
        metric.putAttribute("network_type", firebaseTelemetry.refreshNetworkTypeKey())
        sourceId?.let {
            firebaseTelemetry.setActiveSource(it)
            metric.putAttribute("scraper_source", it)
        }
        metric.start()

        try {
            var lastException: IOException? = null
            repeat(runtimeConfig.retryCount + 1) { attempt ->
                try {
                    val response = tunedChain.proceed(request)
                    metric.setHttpResponseCode(response.code)
                    response.body?.contentLength()?.takeIf { it >= 0 }?.let(metric::setResponsePayloadSize)
                    return response
                } catch (exception: IOException) {
                    lastException = exception
                    if (attempt == runtimeConfig.retryCount) throw exception
                }
            }

            throw lastException ?: IOException("Unknown scraper network error")
        } finally {
            metric.stop()
        }
    }
}
