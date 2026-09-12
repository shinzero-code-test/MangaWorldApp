package com.exapps.mangaworld.presentation.auth
import com.exapps.mangaworld.R
import android.content.Context
import androidx.compose.ui.res.stringResource

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.AccountMergeRequiredException
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSignedIn: Boolean = false,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val username: String = "",
    val passwordResetSent: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager,
    private val communityRepository: com.exapps.mangaworld.domain.repository.CommunityRepository,
    private val securityRepository: com.exapps.mangaworld.domain.repository.SecurityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.authState.collect { user ->
                _uiState.update { it.copy(isSignedIn = user != null && !user.isAnonymous) }
            }
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email.trim(), error = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun onDisplayNameChanged(displayName: String) {
        _uiState.update { it.copy(displayName = displayName, error = null) }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username.trim(), error = null) }
    }

    fun signInWithEmail(email: String, password: String) {
        val normalizedEmail = email.trim()
        _uiState.update { it.copy(email = normalizedEmail) }
        // A-4: validate format + Firebase's 6-char minimum client-side so
        // obvious typos never reach the backend.
        validateEmailPassword(normalizedEmail, password)?.let { error ->
            _uiState.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signInWithEmail(normalizedEmail, password)
                if (uid != null) {
                    runCatching { securityRepository.recordSignIn("password") }
                    // A-5: never retain the raw password in state after submit.
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true, password = "") }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.auth_error_login_failed), password = "") }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(context, error.reason), password = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e), password = "") }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, displayName: String = "", username: String = "") {
        val normalizedEmail = email.trim()
        // A-4: same client-side gates as login.
        validateEmailPassword(normalizedEmail, password)?.let { error ->
            _uiState.update { it.copy(error = error) }
            return
        }
        if (displayName.isBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.auth_error_display_name_required)) }
            return
        }
        if (username.isBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.enter_username)) }
            return
        }
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.length !in 3..20 || !normalizedUsername.matches(com.exapps.mangaworld.domain.UsernameRules.REGEX)) {
            _uiState.update { it.copy(error = context.getString(R.string.str_108)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signUpWithEmail(normalizedEmail, password, displayName.trim(), normalizedUsername)
                if (uid != null) {
                    // Create the Firestore profile with the username. A-3: a
                    // username collision must surface (the account already
                    // exists at this point, so the user stays signed in but is
                    // told to pick another name). Any other save failure keeps
                    // the v8.4.2 behavior: silent success, profile screens
                    // self-heal on next visit.
                    val profileResult = runCatching {
                        communityRepository.upsertProfile(
                            username = normalizedUsername,
                            bio = "",
                            isPublic = true,
                            displayName = displayName.trim()
                        )
                    }
                    profileResult.exceptionOrNull()?.let { failure ->
                        if (isUsernameClaimFailure(failure)) {
                            runCatching { securityRepository.recordSignIn("password") }
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isSignedIn = true,
                                    password = "",
                                    error = context.getString(R.string.auth_error_username_taken)
                                )
                            }
                            return@launch
                        }
                    }
                    runCatching { securityRepository.recordSignIn("password") }
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true, password = "") }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.auth_error_signup_failed), password = "") }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(context, error.reason), password = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e), password = "") }
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signInWithGoogleIdToken(idToken)
                if (uid != null) {
                    // Ensure profile exists with the provider's display name
                    ensureProfileExists(uid)
                    runCatching { securityRepository.recordSignIn("google.com") }
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.str_335)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(context, error.reason)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    fun signInWithFacebook(accessToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signInWithFacebook(accessToken)
                if (uid != null) {
                    // Ensure profile exists with the provider's display name
                    ensureProfileExists(uid)
                    runCatching { securityRepository.recordSignIn("facebook.com") }
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.str_334)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(context, error.reason)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    /**
     * After social sign-in, provision the Firestore profile (display name =
     * provider account name, username = email local-part) or repair a blank
     * one. Shared helper — CloudSyncViewModel uses the same call.
     */
    private suspend fun ensureProfileExists(uid: String) {
        runCatching {
            communityRepository.ensureSocialProfile(sessionManager.currentUser(), uid)
        }
    }

    fun sendPasswordReset(email: String) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.enter_email)) }
            return
        }
        if (!isValidEmail(normalizedEmail)) {
            _uiState.update { it.copy(error = context.getString(R.string.invalid_email)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, passwordResetSent = false) }
            try {
                sessionManager.sendPasswordResetEmail(normalizedEmail)
                _uiState.update { it.copy(isLoading = false, passwordResetSent = true, error = null) }
            } catch (e: Exception) {
                // A-2: never reveal whether the email is registered. An unknown
                // email reports the same success the user sees for a real one.
                if (isUserNotFound(e)) {
                    _uiState.update { it.copy(isLoading = false, passwordResetSent = true, error = null) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = mapAuthError(e), passwordResetSent = false) }
                }
            }
        }
    }

    fun clearPasswordResetSent() {
        _uiState.update { it.copy(passwordResetSent = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Social sign-in failed without an exception to map (null token, provider error). */
    fun onSocialSignInFailed() {
        _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.auth_error_login_failed)) }
    }

    private fun mapAuthError(error: Exception): String = firebaseAuthErrorMessage(context, error)

    /**
     * A-4: blank → format → Firebase 6-char minimum, using existing strings.
     * Returns the error message, or null when the pair is submittable.
     */
    private fun validateEmailPassword(email: String, password: String): String? {
        if (email.isBlank() || password.isBlank()) {
            return context.getString(R.string.auth_error_empty_fields)
        }
        if (!isValidEmail(email)) {
            return context.getString(R.string.invalid_email)
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            return context.getString(R.string.auth_error_password_weak)
        }
        return null
    }

    /**
     * A-3: upsertProfile reports a taken username via require() with the
     * auth_error_username_taken message. Only that case surfaces — every other
     * save failure stays silent-success with self-heal.
     */
    private fun isUsernameClaimFailure(failure: Throwable): Boolean =
        failure is IllegalArgumentException &&
            failure.message == context.getString(R.string.auth_error_username_taken)

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
        // Pure-Kotlin email gate (A-4): android.util.Patterns is null on JVM
        // unit tests and varies by OS version — the server remains the source
        // of truth, this only catches obvious typos client-side.
        val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }

    private fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email)
}
