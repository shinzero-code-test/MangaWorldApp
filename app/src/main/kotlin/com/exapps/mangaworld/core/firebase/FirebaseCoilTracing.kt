package com.exapps.mangaworld.core.firebase

import android.util.Log
import coil.request.ImageRequest
import com.google.firebase.perf.FirebasePerformance

private const val TAG = "CoilTracing"
private const val TRACE_NAME = "coil_image_load"

/**
 * Lightweight sampling: only trace ~10% of image loads to avoid exceeding
 * Firebase Performance custom trace limits. Uses a single shared trace
 * with counter metrics instead of creating a new trace per image.
 */
private var loadCount = 0L
private val sharedTrace by lazy {
    FirebasePerformance.getInstance().newTrace(TRACE_NAME).apply {
        putAttribute("sampling", "counter_based")
        start()
    }
}

fun ImageRequest.Builder.withFirebaseTrace(surface: String): ImageRequest.Builder {
    // Sample 10% of image loads
    loadCount++
    if (loadCount % 10 != 0L) return this

    sharedTrace.incrementMetric("load_count", 1)
    sharedTrace.incrementMetric("surface_${surface.take(16)}", 1)

    return listener(
        onStart = { _ -> },
        onSuccess = { _, _ -> sharedTrace.incrementMetric("success_count", 1) },
        onError = { _, throwable ->
            sharedTrace.incrementMetric("failure_count", 1)
            Log.w(TAG, "Image load failed: ${throwable.toString()}")
        },
        onCancel = { _ -> sharedTrace.incrementMetric("cancel_count", 1) }
    )
}
