package com.exapps.mangaworld.core.firebase

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSessionManager @Inject constructor() {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    suspend fun ensureGuestSession(): String? {
        auth.currentUser?.uid?.let { return it }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    fun currentUserId(): String? = auth.currentUser?.uid
}
