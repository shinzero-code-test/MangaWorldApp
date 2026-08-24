package com.exapps.mangaworld.core.data.remote.scraper

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Static telemetry hook for scraper failures.
 *
 * Scrapers are constructed by explicit @Provides factories without access to
 * the injectable [com.exapps.mangaworld.core.firebase.FirebaseTelemetry], so
 * this thin wrapper exposes Crashlytics reporting statically.
 */
object ScraperTelemetry {
    fun logFailure(sourceId: String, stage: String, throwable: Throwable) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("scraper_source", sourceId)
            crashlytics.setCustomKey("scraper_stage", stage)
            crashlytics.recordException(throwable)
        }
    }
}
