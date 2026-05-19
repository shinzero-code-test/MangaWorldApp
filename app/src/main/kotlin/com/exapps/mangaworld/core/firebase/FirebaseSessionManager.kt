package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
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
        return runCatching {
            when {
                current == null -> auth.signInWithCredential(credential).await().user?.uid
                current.isAnonymous -> current.linkWithCredential(credential).await().user?.uid
                else -> auth.signInWithCredential(credential).await().user?.uid
            }
        }.getOrElse {
            auth.signInWithCredential(credential).await().user?.uid
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

    suspend fun signOut() {
        googleSignInClient().signOut().await()
        auth.signOut()
        ensureGuestSession()
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
