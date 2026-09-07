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
private val loadCounter = java.util.concurrent.atomic.AtomicLong(0L)

// Allowlisted surfaces — free-form names would explode metric cardinality.
private val ALLOWED_SURFACES = setOf(
    "detail", "reader", "home", "search", "browse", "lists",
    "history", "downloads", "favorites", "notifications"
)

fun ImageRequest.Builder.withFirebaseTrace(surface: String): ImageRequest.Builder {
    // Sample 10% of image loads
    if (loadCounter.incrementAndGet() % 10 != 0L) return this

    // Each sample gets its own short trace that is always stopped, so it
    // flushes. (The old shared never-stopped trace could exceed max duration
    // and never upload.)
    val trace = runCatching {
        FirebasePerformance.getInstance().newTrace(TRACE_NAME).apply {
            putAttribute("sampling", "counter_based")
            start()
        }
    }.getOrNull() ?: return this
    val safeSurface = surface.take(16).takeIf { it in ALLOWED_SURFACES } ?: "other"
    trace.incrementMetric("load_count", 1)
    trace.incrementMetric("surface_$safeSurface", 1)

    return listener(
        onStart = { _ -> },
        onSuccess = { _, _ ->
            trace.incrementMetric("success_count", 1)
            runCatching { trace.stop() }
        },
        onError = { _, throwable ->
            trace.incrementMetric("failure_count", 1)
            Log.w(TAG, "Image load failed: ${throwable.toString()}")
            runCatching { trace.stop() }
        },
        onCancel = { _ ->
            trace.incrementMetric("cancel_count", 1)
            runCatching { trace.stop() }
        }
    )
}
