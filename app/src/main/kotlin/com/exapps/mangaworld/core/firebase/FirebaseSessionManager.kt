package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Maps emails to provider UIDs. Prevents cross-provider duplicate accounts.
 *
 * Collection: `email_registry/{email}` → `{ uid: String, provider: String, createdAt: Long }`
 *
 * Before Facebook/Google sign-in, the email is fetched from the provider's API and checked
 * against this registry. If a different UID already owns the email, sign-in is blocked.
 */
@Singleton
class FirebaseSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val emailRegistry get() = firestore.collection("email_registry")
    private val httpClient = OkHttpClient()

    val authState: Flow<com.google.firebase.auth.FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser).isSuccess
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun ensureGuestSession(): String? {
        auth.currentUser?.uid?.let { return it }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    fun currentUserId(): String? = auth.currentUser?.uid

    fun currentUser(): com.google.firebase.auth.FirebaseUser? = auth.currentUser

    fun googleSignInClient() = GoogleSignIn.getClient(context, googleSignInOptions())

    fun hasGoogleClientId(): Boolean = googleWebClientId().isNotBlank()

    // ─── Email Registry ──────────────────────────────────────────────────────

    /**
     * Check if an email is already registered by a DIFFERENT provider.
     * Returns the provider name (e.g. "google.com") if blocked, null if allowed.
     *
     * Allows re-login with the same provider — only blocks cross-provider duplicates.
     */
    private suspend fun checkEmailBlocked(email: String, thisProvider: String): String? {
        return try {
            val doc = emailRegistry.document(email.lowercase()).get().await()
            val existingProvider = doc.getString("provider")
            val existingUid = doc.getString("uid")
            if (existingUid != null && existingProvider != null && existingProvider != thisProvider) {
                existingProvider
            } else {
                null
            }
        } catch (_: Exception) {
            null // Registry read failure — allow sign-in
        }
    }

    /** Register an email in the registry after successful sign-in. */
    private suspend fun registerEmail(email: String, uid: String, provider: String) {
        runCatching {
            emailRegistry.document(email.lowercase()).set(
                mapOf(
                    "uid" to uid,
                    "provider" to provider,
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()
        }
    }

    // ─── Google ──────────────────────────────────────────────────────────────

    /**
     * Sign in with Google ID token.
     *
     * Before signing in, checks the `email_registry` to see if the email is already
     * linked to a different account (e.g. Facebook). If so, throws
     * [CrossProviderCollisionException] with a user-friendly message.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): String? {
        val email = getEmailFromIdToken(idToken)

        // Pre-check: is this email already registered with another provider?
        if (email != null) {
            val blocked = checkEmailBlocked(email, "google.com")
            if (blocked != null) {
                throw CrossProviderCollisionException(email, blocked)
            }
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser

        // If anonymous, try linking
        if (current != null && current.isAnonymous) {
            try {
                val result = current.linkWithCredential(credential).await()
                if (email != null) registerEmail(email, result.user!!.uid, "google.com")
                return result.user?.uid
            } catch (_: FirebaseAuthUserCollisionException) {
                auth.signOut()
            } catch (_: Exception) {
                auth.signOut()
            }
        }

        return try {
            val result = auth.signInWithCredential(credential).await()
            val uid = result.user?.uid
            if (email != null && uid != null) registerEmail(email, uid, "google.com")
            uid
        } catch (e: FirebaseAuthUserCollisionException) {
            throw e
        }
    }

    /** Decode email from a Google ID token JWT payload. */
    private fun getEmailFromIdToken(idToken: String): String? {
        return try {
            val parts = idToken.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(payload).optString("email").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // ─── Email ───────────────────────────────────────────────────────────────

    suspend fun signInWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        return runCatching {
            if (current != null && current.isAnonymous) {
                current.linkWithCredential(
                    com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)
                ).await().user?.uid
            } else {
                auth.signInWithEmailAndPassword(email, password).await().user?.uid
            }
        }.getOrElse {
            auth.signInWithEmailAndPassword(email, password).await().user?.uid
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        return runCatching {
            if (current != null && current.isAnonymous) {
                current.linkWithCredential(
                    com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)
                ).await().user?.uid
            } else {
                auth.createUserWithEmailAndPassword(email, password).await().user?.uid
            }
        }.getOrElse {
            auth.createUserWithEmailAndPassword(email, password).await().user?.uid
        }.also { uid ->
            if (uid != null) registerEmail(email, uid, "password")
        }
    }

    // ─── Facebook ────────────────────────────────────────────────────────────

    /**
     * Sign in with Facebook.
     *
     * Before calling Firebase, fetches the user's email from the Facebook Graph API
     * and checks `email_registry` to see if the email is already linked to another
     * account (e.g. Google). If so, throws [CrossProviderCollisionException].
     */
    suspend fun signInWithFacebook(accessToken: String): String? {
        // Fetch email from Facebook Graph API before calling Firebase
        val fbEmail = getFacebookEmail(accessToken)

        // Pre-check: is this email already registered with another provider?
        if (fbEmail != null) {
            val blocked = checkEmailBlocked(fbEmail, "facebook.com")
            if (blocked != null) {
                throw CrossProviderCollisionException(fbEmail, blocked)
            }
        }

        val credential = FacebookAuthProvider.getCredential(accessToken)
        val current = auth.currentUser

        // If anonymous, try linking
        if (current != null && current.isAnonymous) {
            try {
                val result = current.linkWithCredential(credential).await()
                if (fbEmail != null) registerEmail(fbEmail, result.user!!.uid, "facebook.com")
                return result.user?.uid
            } catch (_: FirebaseAuthUserCollisionException) {
                auth.signOut()
            } catch (_: Exception) {
                auth.signOut()
            }
        }

        return try {
            val result = auth.signInWithCredential(credential).await()
            val uid = result.user?.uid
            val email = fbEmail ?: result.user?.email
            if (email != null && uid != null) registerEmail(email, uid, "facebook.com")
            uid
        } catch (e: FirebaseAuthUserCollisionException) {
            // Firebase detected the collision (verified email) — we know it's another provider
            throw CrossProviderCollisionException(
                e.email ?: fbEmail ?: "unknown",
                "another provider"
            )
        }
    }

    /** Fetch user email from the Facebook Graph API using the access token. */
    private suspend fun getFacebookEmail(accessToken: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://graph.facebook.com/me?fields=email&access_token=$accessToken")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                JSONObject(body).optString("email").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ─── Sign Out ────────────────────────────────────────────────────────────

    suspend fun signOut() {
        googleSignInClient().signOut().await()
        auth.signOut()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun googleSignInOptions(): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        googleWebClientId().takeIf { it.isNotBlank() }?.let { builder.requestIdToken(it) }
        return builder.build()
    }

    private fun googleWebClientId(): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) "" else context.getString(id)
    }
}

/**
 * Thrown when a user tries to sign in with a provider whose email is already registered
 * with a different provider.
 *
 * @property email The conflicting email address.
 * @property existingProvider The provider that already owns this email (e.g. "google.com").
 */
class CrossProviderCollisionException(
    val email: String,
    val existingProvider: String
) : Exception("Email $email is already registered with $existingProvider")
