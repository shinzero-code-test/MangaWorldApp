package com.exapps.mangaworld.core.firebase

import com.google.firebase.perf.FirebasePerformance
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseNetworkInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val metric = FirebasePerformance.getInstance().newHttpMetric(request.url.toString(), request.method)
        metric.start()
        return try {
            val response = chain.proceed(request)
            metric.setHttpResponseCode(response.code)
            response.body?.contentLength()?.takeIf { it >= 0 }?.let(metric::setResponsePayloadSize)
            response
        } finally {
            metric.stop()
        }
    }
}
