package com.exapps.mangaworld.core.firebase

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessagingRegistrar"

@Singleton
class FirebaseMessagingRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val messaging = FirebaseMessaging.getInstance()

    /** Last persisted token — skips Firestore write if unchanged. */
    @Volatile private var lastPersistedToken: String? = null

    private val messagingPrefs by lazy {
        context.getSharedPreferences("messaging_prefs", Context.MODE_PRIVATE)
    }

    suspend fun syncCurrentToken() {
        val token = runCatching { messaging.token.await() }.getOrNull() ?: return
        if (token == lastPersistedToken) return  // Skip if token hasn't changed
        // Memory-only dedup loses state on process restart — confirm against prefs
        // so every cold start doesn't re-read the devices collection.
        if (token == messagingPrefs.getString("last_token", null) &&
            messagingPrefs.getString("device_doc_id", null) != null
        ) {
            lastPersistedToken = token
            return
        }
        persistToken(token)
    }

    suspend fun onTokenRefreshed(token: String) {
        lastPersistedToken = null  // Force re-persist on refresh
        persistToken(token)
    }

    private suspend fun persistToken(token: String) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val deviceDocId = token.sha256().take(32)
        val previousDocId = messagingPrefs.getString("device_doc_id", null)

        // Device-scoped cleanup only: never delete sibling devices' docs (each
        // device owns exactly one doc). Write-then-delete is crash-safe — a
        // crash leaves at most one stale self doc, never zero registrations.
        // Sibling cleanup belongs server-side with an updatedAt TTL.
        firestore.collection("users")
            .document(uid)
            .collection("devices")
            .document(deviceDocId)
            .set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "notificationsGranted" to notificationsGranted(),
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()

        if (previousDocId != null && previousDocId != deviceDocId) {
            runCatching {
                firestore.collection("users").document(uid)
                    .collection("devices").document(previousDocId).delete().await()
            }.onFailure { e ->
                Log.w(TAG, "Failed to delete previous device doc: ${e.message}")
            }
        }

        messagingPrefs.edit()
            .putString("device_doc_id", deviceDocId)
            .putString("last_token", token)
            .apply()
        lastPersistedToken = token
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte -> append(String.format("%02x", byte.toInt() and 0xff)) }
    }
}
