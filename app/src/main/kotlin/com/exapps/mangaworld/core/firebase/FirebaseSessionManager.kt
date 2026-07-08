package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun signInWithGoogleIdToken(idToken: String): String? {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser

        // If anonymous user, try linking Google to the guest account first
        if (current != null && current.isAnonymous) {
            try {
                return current.linkWithCredential(credential).await().user?.uid
            } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                // Email exists with another provider — sign out anonymous, fall through to direct sign-in
                auth.signOut()
            } catch (_: Exception) {
                auth.signOut()
            }
        }

        return try {
            auth.signInWithCredential(credential).await().user?.uid
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            throw e
        }
    }

    suspend fun signInWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        return runCatching {
            if (current != null && current.isAnonymous) {
                current.linkWithCredential(com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)).await().user?.uid
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
                current.linkWithCredential(com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)).await().user?.uid
            } else {
                auth.createUserWithEmailAndPassword(email, password).await().user?.uid
            }
        }.getOrElse {
            auth.createUserWithEmailAndPassword(email, password).await().user?.uid
        }
    }

    suspend fun signInWithFacebook(accessToken: String): String? {
        val credential = FacebookAuthProvider.getCredential(accessToken)
        val current = auth.currentUser

        // If anonymous user, try linking Facebook to the guest account first
        if (current != null && current.isAnonymous) {
            try {
                return current.linkWithCredential(credential).await().user?.uid
            } catch (linkError: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                // Email exists with another provider — fall through to direct sign-in below,
                // which will also detect the collision
            } catch (_: Exception) {
                // Other linking error — fall through to direct sign-in
            }
            // Sign out the anonymous user so signInWithCredential starts fresh
            auth.signOut()
        }

        // Sign in with Facebook credential
        val result = try {
            auth.signInWithCredential(credential).await()
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            // Firebase directly detected the collision — email is already linked to another provider
            throw e
        }

        val user = result.user ?: return null

        // Post-sign-in duplicate detection: Facebook may succeed without throwing if the
        // email is unverified. Check if the email is already linked to another provider.
        val email = user.email
        if (email != null) {
            try {
                val methods = auth.fetchSignInMethodsForEmail(email).await().signInMethods.orEmpty()
                if (methods.any { it != "facebook.com" }) {
                    // Duplicate detected — another provider (Google, email, etc.) owns this email.
                    // Delete the freshly created duplicate and throw.
                    user.delete().await()
                    auth.signOut()
                    throw com.google.firebase.auth.FirebaseAuthUserCollisionException(
                        "auth/email-already-in-use",
                        com.google.firebase.auth.EmailAuthProvider.getCredential(email, "")
                    )
                }
            } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                throw e // re-throw our own collision
            } catch (_: Exception) {
                // fetchSignInMethodsForEmail can fail (e.g., not enabled) — let the account stand
            }
        }

        return user.uid
    }

    suspend fun signOut() {
        googleSignInClient().signOut().await()
        auth.signOut()
        // Do NOT call ensureGuestSession() — user should be fully signed out
    }

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
