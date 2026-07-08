package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a pending [com.google.firebase.auth.AuthCredential] when account linking is required.
 * Set by [signInWithFacebook] when collision is detected; consumed by [linkPendingFacebookCredential]
 * after the user signs in with the existing provider (e.g. Google).
 */
@Volatile
var pendingFacebookCredential: com.google.firebase.auth.AuthCredential? = null
    private set

@Singleton
class FirebaseSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val authState: Flow<com.google.firebase.auth.FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth -> trySend(firebaseAuth.currentUser).isSuccess }
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

    // ─── Google ──────────────────────────────────────────────────────────────

    suspend fun signInWithGoogleIdToken(idToken: String): String? {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser

        // If anonymous user, try linking Google to the guest account first
        if (current != null && current.isAnonymous) {
            try {
                return current.linkWithCredential(credential).await().user?.uid
            } catch (e: FirebaseAuthUserCollisionException) {
                // Email exists with another provider — sign out anonymous, fall through
                auth.signOut()
            } catch (_: Exception) {
                auth.signOut()
            }
        }

        return try {
            val result = auth.signInWithCredential(credential).await()
            // After successful Google sign-in, check if there's a pending Facebook credential to link
            linkPendingFacebookCredential()
            result.user?.uid
        } catch (e: FirebaseAuthUserCollisionException) {
            throw e
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
        }
    }

    // ─── Facebook ────────────────────────────────────────────────────────────

    /**
     * Sign in with Facebook.
     *
     * Follows the official Firebase account-linking pattern:
     * 1. If user is anonymous → try `linkWithCredential` to upgrade the guest account
     * 2. If collision → the email is already linked to another provider (e.g. Google).
     *    Store the pending Facebook credential and throw so the UI can prompt the user
     *    to sign in with the existing provider. After that sign-in succeeds,
     *    [linkPendingFacebookCredential] is called to merge Facebook into the existing account.
     * 3. If no current user → `signInWithCredential` directly. On collision, same as above.
     */
    suspend fun signInWithFacebook(accessToken: String): String? {
        val credential = FacebookAuthProvider.getCredential(accessToken)
        val current = auth.currentUser

        // If anonymous user, try linking Facebook to the guest account
        if (current != null && current.isAnonymous) {
            try {
                return current.linkWithCredential(credential).await().user?.uid
            } catch (e: FirebaseAuthUserCollisionException) {
                // Collision — email is already linked to another provider.
                // Store the credential so it can be linked after the user signs in
                // with the existing provider (Google, email, etc.).
                pendingFacebookCredential = credential
                throw e
            }
        }

        // Non-anonymous or no current user — sign in directly
        return try {
            auth.signInWithCredential(credential).await().user?.uid
        } catch (e: FirebaseAuthUserCollisionException) {
            pendingFacebookCredential = credential
            throw e
        }
    }

    /**
     * Called after a successful sign-in with another provider (e.g. Google) to link
     * the pending Facebook credential to the now-signed-in user's account.
     *
     * This completes the merge: the existing Google account now also has Facebook linked.
     */
    suspend fun linkPendingFacebookCredential(): Boolean {
        val pending = pendingFacebookCredential ?: return false
        val user = auth.currentUser ?: return false
        return try {
            user.linkWithCredential(pending).await()
            true
        } catch (_: Exception) {
            false
        } finally {
            pendingFacebookCredential = null
        }
    }

    // ─── Sign Out ────────────────────────────────────────────────────────────

    suspend fun signOut() {
        pendingFacebookCredential = null
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
