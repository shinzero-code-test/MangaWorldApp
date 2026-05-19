package com.exapps.mangaworld.core.firebase

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTelemetry @Inject constructor() {
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val performance = FirebasePerformance.getInstance()

    fun logScraperFailure(sourceId: String, stage: String, throwable: Throwable) {
        crashlytics.log("scraper_failure source=$sourceId stage=$stage")
        crashlytics.setCustomKey("scraper_source", sourceId)
        crashlytics.setCustomKey("scraper_stage", stage)
        crashlytics.recordException(throwable)
    }

    fun <T> trace(name: String, block: () -> T): T {
        val trace = performance.newTrace(name)
        trace.start()
        return try {
            block()
        } finally {
            trace.stop()
        }
    }
}
