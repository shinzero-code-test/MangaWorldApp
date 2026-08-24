package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.facebook.login.LoginManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
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
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.buffer(Channel.CONFLATED)

    /**
     * Returns the active Firebase UID, creating an anonymous guest session only when no user exists.
     *
     * Firebase preserves an anonymous UID after a successful credential link. If linking collides
     * with an existing account, the fallback signs into that account and cannot merge guest-owned
     * Firestore data client-side because the active UID changes.
     *
     * Returns null only when Firebase cannot create an anonymous session.
     */
    suspend fun ensureFirebaseSession(): String? {
        auth.currentUser?.uid?.let { return it }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    fun currentUserId(): String? = auth.currentUser?.uid

    fun currentUser(): com.google.firebase.auth.FirebaseUser? = auth.currentUser

    /**
     * ID token for dashboard mutation calls (vote / push-reply / Cloudinary /
     * moderation check). Defaults to force-refresh: these are low-frequency
     * operations, and the cached token can be past expiry after idle periods,
     * turning mutations into silent 401s (M-review).
     */
    suspend fun currentIdToken(forceRefresh: Boolean = true): String? =
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token

    fun linkedProviderIds(): Set<String> = auth.currentUser?.providerData
        ?.map { it.providerId }
        ?.filterNot { it == FirebaseAuthProvider.PROVIDER_ID }
        ?.toSet()
        .orEmpty()

    fun googleSignInClient() = GoogleSignIn.getClient(context, googleSignInOptions())

    fun hasGoogleClientId(): Boolean = googleWebClientId().isNotBlank()

    // ─── Google ──────────────────────────────────────────────────────────────

    suspend fun signInWithGoogleIdToken(idToken: String): String? {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            // Try linking first; if collision, the credential belongs to an existing account — sign in directly
            return linkOrSignIn(current, credential)
        }
        return auth.signInWithCredential(credential).await().user?.uid
    }

    suspend fun linkGoogle(idToken: String): String =
        linkCredential(requireNamedUserForProviderManagement(), GoogleAuthProvider.getCredential(idToken, null))

    // ─── Email ───────────────────────────────────────────────────────────────

    suspend fun signInWithEmail(email: String, password: String): String? {
        val current = auth.currentUser
        val credential = EmailAuthProvider.getCredential(email, password)
        return if (current != null && current.isAnonymous) linkOrSignIn(current, credential)
        else auth.signInWithEmailAndPassword(email, password).await().user?.uid
    }

    suspend fun signUpWithEmail(email: String, password: String, displayName: String = "", username: String = ""): String? {
        val current = auth.currentUser
        val credential = EmailAuthProvider.getCredential(email, password)
        val uid = if (current != null && current.isAnonymous) linkOrSignIn(current, credential)
            else auth.createUserWithEmailAndPassword(email, password).await().user?.uid
        // Set displayName on Firebase Auth profile so it persists
        if (uid != null && displayName.isNotBlank()) {
            auth.currentUser?.updateProfile(
                com.google.firebase.auth.UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build()
            )?.await()
        }
        return uid
    }

    suspend fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    suspend fun linkEmailPassword(email: String, password: String): String =
        linkCredential(requireNamedUserForProviderManagement(), EmailAuthProvider.getCredential(email, password))

    // ─── Facebook ────────────────────────────────────────────────────────────

    suspend fun signInWithFacebook(accessToken: String): String? {
        val credential = FacebookAuthProvider.getCredential(accessToken)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            // Try linking first; if collision, the credential belongs to an existing account — sign in directly
            return linkOrSignIn(current, credential)
        }
        return auth.signInWithCredential(credential).await().user?.uid
    }

    suspend fun linkFacebook(accessToken: String): String =
        linkCredential(requireNamedUserForProviderManagement(), FacebookAuthProvider.getCredential(accessToken))

    suspend fun unlinkProvider(providerId: String) {
        val user = requireNamedUserForProviderManagement()
        require(linkedProviderIds().size > 1) { "Keep at least one sign-in provider" }
        user.unlink(providerId).await()
    }

    // ─── Sign Out ────────────────────────────────────────────────────────────

    suspend fun signOut() {
        try {
            googleSignInClient().signOut().await()
        } finally {
            try {
                LoginManager.getInstance().logOut()
            } finally {
                auth.signOut()
            }
        }
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

    private fun requireNamedUserForProviderManagement(): com.google.firebase.auth.FirebaseUser {
        val user = auth.currentUser
            ?: throw ProviderManagementRequiresSignInException(isGuestSession = false)
        if (user.isAnonymous) {
            throw ProviderManagementRequiresSignInException(isGuestSession = true)
        }
        return user
    }

    private suspend fun linkCredential(
        user: com.google.firebase.auth.FirebaseUser,
        credential: com.google.firebase.auth.AuthCredential
    ): String = try {
        linkedUserId(user, credential)
    } catch (error: FirebaseAuthUserCollisionException) {
        throw AccountMergeRequiredException(error)
    }

    /**
     * Try to link the credential to the anonymous user. Firebase permits a direct sign-in
     * fallback only when the credential or email is already linked; a different-credential
     * collision requires the user to first authenticate with the original provider.
     */
    private suspend fun linkOrSignIn(
        user: com.google.firebase.auth.FirebaseUser,
        credential: com.google.firebase.auth.AuthCredential
    ): String? = try {
        linkedUserId(user, credential)
    } catch (collision: FirebaseAuthUserCollisionException) {
        if (!collision.allowsSignInFallback()) {
            throw AccountMergeRequiredException(collision)
        }
        signInAfterLinkCollision(credential, collision)
    }

    private suspend fun linkedUserId(
        user: com.google.firebase.auth.FirebaseUser,
        credential: com.google.firebase.auth.AuthCredential
    ): String = checkNotNull(user.linkWithCredential(credential).await().user) {
        "Firebase did not return a user after linking a credential"
    }.uid

    private suspend fun signInAfterLinkCollision(
        credential: com.google.firebase.auth.AuthCredential,
        initialCollision: FirebaseAuthUserCollisionException
    ): String? = try {
        auth.signInWithCredential(credential).await().user?.uid
    } catch (retryCollision: FirebaseAuthUserCollisionException) {
        throw AccountMergeRequiredException(retryCollision).also {
            it.addSuppressed(initialCollision)
        }
    }
}

enum class AccountMergeReason {
    ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
    CREDENTIAL_ALREADY_IN_USE,
    EMAIL_ALREADY_IN_USE,
    UNKNOWN
}

class ProviderManagementRequiresSignInException(
    val isGuestSession: Boolean
) : IllegalStateException(
    if (isGuestSession) {
        "Upgrade the guest session before managing linked providers"
    } else {
        "Sign in before managing linked providers"
    }
)

class AccountMergeRequiredException(
    val reason: AccountMergeReason,
    val errorCode: String,
    collision: FirebaseAuthUserCollisionException
) : IllegalStateException(
    "Firebase account merge is required (reason=$reason, errorCode=$errorCode): " +
        collision.message.orEmpty(),
    collision
) {
    constructor(collision: FirebaseAuthUserCollisionException) : this(
        reason = collision.toAccountMergeReason(),
        errorCode = collision.errorCode,
        collision = collision
    )
}

private fun FirebaseAuthUserCollisionException.allowsSignInFallback(): Boolean =
    when (toAccountMergeReason()) {
        AccountMergeReason.CREDENTIAL_ALREADY_IN_USE,
        AccountMergeReason.EMAIL_ALREADY_IN_USE -> true
        AccountMergeReason.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
        AccountMergeReason.UNKNOWN -> false
    }

private fun FirebaseAuthUserCollisionException.toAccountMergeReason(): AccountMergeReason =
    when (errorCode) {
        ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL ->
            AccountMergeReason.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL
        ERROR_CREDENTIAL_ALREADY_IN_USE -> AccountMergeReason.CREDENTIAL_ALREADY_IN_USE
        ERROR_EMAIL_ALREADY_IN_USE -> AccountMergeReason.EMAIL_ALREADY_IN_USE
        else -> AccountMergeReason.UNKNOWN
    }

private const val ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL =
    "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"
private const val ERROR_CREDENTIAL_ALREADY_IN_USE = "ERROR_CREDENTIAL_ALREADY_IN_USE"
private const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
