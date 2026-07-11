package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
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

@Singleton
class FirebaseSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

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

    suspend fun currentIdToken(): String? = auth.currentUser?.getIdToken(false)?.await()?.token

    fun linkedProviderIds(): Set<String> = auth.currentUser?.providerData
        ?.map { it.providerId }
        ?.filterNot { it == "firebase" }
        ?.toSet()
        .orEmpty()

    fun googleSignInClient() = GoogleSignIn.getClient(context, googleSignInOptions())

    fun hasGoogleClientId(): Boolean = googleWebClientId().isNotBlank()

    // ─── Google ──────────────────────────────────────────────────────────────

    suspend fun signInWithGoogleIdToken(idToken: String): String? {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            return linkCredential(current, credential)
        }
        return auth.signInWithCredential(credential).await().user?.uid
    }

    suspend fun linkGoogle(idToken: String): String =
        linkCredential(requireAuthenticatedUser(), GoogleAuthProvider.getCredential(idToken, null))

    // ─── Email ───────────────────────────────────────────────────────────────

    suspend fun signInWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        val credential = EmailAuthProvider.getCredential(email, password)
        return if (current != null && current.isAnonymous) linkCredential(current, credential)
        else auth.signInWithEmailAndPassword(email, password).await().user?.uid
    }

    suspend fun signUpWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        val credential = EmailAuthProvider.getCredential(email, password)
        return if (current != null && current.isAnonymous) linkCredential(current, credential)
        else auth.createUserWithEmailAndPassword(email, password).await().user?.uid
    }

    suspend fun linkEmailPassword(email: String, password: String): String =
        linkCredential(requireAuthenticatedUser(), EmailAuthProvider.getCredential(email, password))

    // ─── Facebook ────────────────────────────────────────────────────────────

    suspend fun signInWithFacebook(accessToken: String): String? {
        val credential = FacebookAuthProvider.getCredential(accessToken)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            return linkCredential(current, credential)
        }
        return auth.signInWithCredential(credential).await().user?.uid
    }

    suspend fun linkFacebook(accessToken: String): String =
        linkCredential(requireAuthenticatedUser(), FacebookAuthProvider.getCredential(accessToken))

    suspend fun unlinkProvider(providerId: String) {
        val user = requireAuthenticatedUser()
        require(linkedProviderIds().size > 1) { "Keep at least one sign-in provider" }
        user.unlink(providerId).await()
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

    private fun requireAuthenticatedUser(): com.google.firebase.auth.FirebaseUser =
        requireNotNull(auth.currentUser?.takeUnless { it.isAnonymous }) { "Sign in before linking a provider" }

    private suspend fun linkCredential(
        user: com.google.firebase.auth.FirebaseUser,
        credential: com.google.firebase.auth.AuthCredential
    ): String = try {
        requireNotNull(user.linkWithCredential(credential).await().user).uid
    } catch (error: FirebaseAuthUserCollisionException) {
        throw AccountMergeRequiredException(error.errorCode)
    }
}

class AccountMergeRequiredException(errorCode: String) :
    Exception("The credential is already linked to another account: $errorCode")
