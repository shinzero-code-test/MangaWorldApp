package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.os.Build
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.firebase.CloudinaryUploader.Companion.DASHBOARD_BASE_URL
import com.exapps.mangaworld.domain.model.DeviceEntry
import com.exapps.mangaworld.domain.model.LoginLogEntry
import com.exapps.mangaworld.domain.model.SessionEntry
import com.exapps.mangaworld.domain.repository.SecurityRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Security centre backend (Phase 2, item 2c).
 *
 * - Login logs: one doc per sign-in under users/{uid}/loginLogs, capped at 50.
 * - Devices: reuses the FCM registry written by [FirebaseMessagingRegistrar]
 *   (doc id = sha256(token).take(32) — mirrored here to link sessions).
 * - Sessions: users/{uid}/sessions/{sessionId}; the id lives in
 *   SharedPreferences so "current" can be flagged without a token lookup.
 *
 * Firebase has no per-session token revocation: revoking one session cuts its
 * push delivery + flags it, while [signOutAllDevices] performs the true
 * invalidation (Admin SDK revokeRefreshTokens) via the dashboard.
 */
@Singleton
class FirebaseSecurityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager,
    private val auth: FirebaseAuth,
    private val okHttpClient: OkHttpClient
) : SecurityRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val messaging = FirebaseMessaging.getInstance()

    private val securityPrefs by lazy {
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
    }

    private fun uid(): String? {
        val id = sessionManager.currentUserId() ?: return null
        if (auth.currentUser?.isAnonymous == true) return null
        return id
    }

    private fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64).ifBlank { "Android" }

    // ─── Login logs ─────────────────────────────────────────────────────────

    override fun observeLoginLogs(limit: Int): Flow<List<LoginLogEntry>> = callbackFlow {
        val id = uid()
        if (id == null) { trySend(emptyList()); close(); return@callbackFlow }
        val reg = firestore.collection("users").document(id).collection("loginLogs")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong())
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val createdAt = doc.getLong("createdAt") ?: return@mapNotNull null
                    LoginLogEntry(
                        id = doc.id,
                        createdAt = createdAt,
                        deviceLabel = doc.getString("deviceLabel").orEmpty(),
                        appVersion = doc.getString("appVersion").orEmpty(),
                        provider = doc.getString("provider").orEmpty()
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun deleteLoginLog(id: String) {
        val uid = uid() ?: return
        firestore.collection("users").document(uid).collection("loginLogs").document(id)
            .delete().await()
    }

    override suspend fun clearLoginLogs() {
        val uid = uid() ?: return
        firestore.collection("users").document(uid).collection("loginLogs")
            .get().await().documents.forEach { it.reference.delete().await() }
    }

    // ─── Devices ────────────────────────────────────────────────────────────

    override fun observeDevices(): Flow<List<DeviceEntry>> = callbackFlow {
        val uid = uid()
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        val currentDocId = securityPrefs.getString("device_doc_id", null)
            ?: context.getSharedPreferences("messaging_prefs", Context.MODE_PRIVATE)
                .getString("device_doc_id", null)
        val reg = firestore.collection("users").document(uid).collection("devices")
            .orderBy("updatedAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val token = doc.getString("token").orEmpty()
                    if (token.isBlank()) return@mapNotNull null
                    DeviceEntry(
                        id = doc.id,
                        tokenSuffix = token.takeLast(8),
                        platform = doc.getString("platform").orEmpty(),
                        sdkInt = doc.getLong("sdkInt")?.toInt() ?: 0,
                        updatedAt = doc.getLong("updatedAt") ?: 0L,
                        isCurrent = doc.id == currentDocId
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun removeDevice(id: String) {
        val uid = uid() ?: return
        firestore.collection("users").document(uid).collection("devices").document(id)
            .delete().await()
    }

    // ─── Sessions ───────────────────────────────────────────────────────────

    override fun observeSessions(): Flow<List<SessionEntry>> = callbackFlow {
        val uid = uid()
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        val currentId = securityPrefs.getString("session_id", null)
        val reg = firestore.collection("users").document(uid).collection("sessions")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    SessionEntry(
                        id = doc.id,
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        lastSeenAt = doc.getLong("lastSeenAt") ?: 0L,
                        deviceLabel = doc.getString("deviceLabel").orEmpty(),
                        appVersion = doc.getString("appVersion").orEmpty(),
                        revoked = doc.getBoolean("revoked") == true,
                        isCurrent = doc.id == currentId
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun revokeSession(id: String) {
        val uid = uid() ?: return
        val sessions = firestore.collection("users").document(uid).collection("sessions")
        val currentId = securityPrefs.getString("session_id", null)
        // Cut push delivery to the linked device, then flag the session.
        val deviceId = runCatching {
            sessions.document(id).get().await().getString("deviceId")
        }.getOrNull()
        if (!deviceId.isNullOrBlank()) {
            runCatching {
                firestore.collection("users").document(uid)
                    .collection("devices").document(deviceId).delete().await()
            }
        }
        sessions.document(id).set(mapOf("revoked" to true), SetOptions.merge()).await()
        if (id == currentId) {
            runCatching { sessionManager.signOut() }
        }
    }

    override suspend fun signOutAllDevices() {
        val uid = uid() ?: run {
            // No uid (guest): local sign-out is the whole operation.
            runCatching { sessionManager.signOut() }
            return
        }
        val token = auth.currentUser?.getIdToken(false)?.await()?.token
            ?: error("No ID token")
        val request = Request.Builder()
            .url("$DASHBOARD_BASE_URL/api/users/me/revoke-sessions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        val code = okHttpClient.newCall(request).execute().use { it.code }
        if (code !in 200..299) error("Revoke failed: $code")
        // Server wiped devices/sessions + revoked refresh tokens — drop local state.
        securityPrefs.edit().remove("session_id").apply()
        runCatching { sessionManager.signOut() }
    }

    // ─── Recording ──────────────────────────────────────────────────────────

    override suspend fun recordSignIn(provider: String) {
        val uid = uid() ?: return
        val now = System.currentTimeMillis()
        firestore.collection("users").document(uid).collection("loginLogs")
            .add(
                mapOf(
                    "createdAt" to now,
                    "deviceLabel" to deviceLabel(),
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "provider" to provider.take(32)
                )
            ).await()
        // Cap history at 50 entries (oldest pruned).
        val overflow = firestore.collection("users").document(uid).collection("loginLogs")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(200)
            .get().await().documents.drop(MAX_LOGIN_LOGS)
        overflow.forEach { runCatching { it.reference.delete().await() } }
        ensureCurrentSession()
    }

    override suspend fun ensureCurrentSession() {
        val uid = uid() ?: return
        var sessionId = securityPrefs.getString("session_id", null)
        if (sessionId.isNullOrBlank()) {
            sessionId = UUID.randomUUID().toString()
            securityPrefs.edit().putString("session_id", sessionId).apply()
        }
        val now = System.currentTimeMillis()
        val ref = firestore.collection("users").document(uid).collection("sessions").document(sessionId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        val createdAt = existing?.getLong("createdAt") ?: now
        // Device linkage mirrors FirebaseMessagingRegistrar's doc-id scheme
        // (sha256(token).take(32)) so revoking a session can cut its pushes.
        val deviceId = runCatching { messaging.token.await() }
            .getOrNull()?.sha256()?.take(32)
        ref.set(
            mapOf(
                "createdAt" to createdAt,
                "lastSeenAt" to now,
                "deviceLabel" to deviceLabel(),
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceId" to deviceId,
                "revoked" to false
            ),
            SetOptions.merge()
        ).await()
    }

    private companion object {
        const val MAX_LOGIN_LOGS = 50
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte -> append(String.format("%02x", byte.toInt() and 0xff)) }
    }
}
