package com.exapps.mangaworld.core.firebase

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Benchmark builds (the app-under-test of the `:benchmark` macrobenchmark
 * module): the app's `src/main` code calls `installAppCheckProvider`, and
 * AGP resolves a build type to `src/<type>` + `src/main` — so this variant
 * needs its own implementation (an `initWith` on a build type copies
 * *configurations*, NOT source sets; without this file `:app:assembleBenchmark`
 * fails to compile with "Unresolved reference").
 *
 * Same posture as the release variant: Play Integrity only, never the debug
 * provider (an attestation-bypass path in a profileable build would also
 * distort the benchmark's network behaviour).
 */
internal fun installAppCheckProvider(firebaseAppCheck: FirebaseAppCheck) {
    try {
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    } catch (_: Exception) {
        Log.w("MangaWorldApp", "Play Integrity unavailable — App Check disabled")
    }
}
