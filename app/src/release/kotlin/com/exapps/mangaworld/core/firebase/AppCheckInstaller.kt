package com.exapps.mangaworld.core.firebase

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds: Play Integrity attestation only. Devices without Google
 * Play services simply run without App Check — the debug provider must never
 * act as a release fallback (attestation-bypass path, M-review).
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
