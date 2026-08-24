package com.exapps.mangaworld.core.firebase

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: use the App Check debug provider so local runs against
 * App Check-enforced backends attest successfully.
 */
internal fun installAppCheckProvider(firebaseAppCheck: FirebaseAppCheck) {
    firebaseAppCheck.installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )
}
