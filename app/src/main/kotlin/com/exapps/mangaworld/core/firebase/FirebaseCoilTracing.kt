package com.exapps.mangaworld.core.firebase

import coil.request.ImageRequest
import com.google.firebase.perf.FirebasePerformance

fun ImageRequest.Builder.withFirebaseTrace(surface: String): ImageRequest.Builder {
    val trace = FirebasePerformance.getInstance().newTrace("coil_image_load")
    var stopped = false
    trace.putAttribute("surface", surface.take(24))
    return listener(
        onStart = { _ ->
            trace.start()
        },
        onSuccess = { _, _ ->
            if (!stopped) {
                stopped = true
                trace.incrementMetric("success_count", 1)
                trace.stop()
            }
        },
        onError = { _, _ ->
            if (!stopped) {
                stopped = true
                trace.incrementMetric("failure_count", 1)
                trace.stop()
            }
        },
        onCancel = { _ ->
            if (!stopped) {
                stopped = true
                trace.incrementMetric("cancel_count", 1)
                trace.stop()
            }
        }
    )
}
