import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.AccountMergeRequiredException
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val sessionManager: FirebaseSessionManager,
    private val communityRepository: com.exapps.mangaworld.domain.repository.CommunityRepository
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
        if (normalizedEmail.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = stringResource(R.string.auth_error_empty_fields)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signInWithEmail(normalizedEmail, password)
                if (uid != null) {
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = stringResource(R.string.auth_error_login_failed)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(error.reason)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, displayName: String = "", username: String = "") {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = stringResource(R.string.auth_error_empty_fields)) }
            return
        }
        if (displayName.isBlank()) {
            _uiState.update { it.copy(error = stringResource(R.string.auth_error_display_name_required)) }
            return
        }
        if (username.isBlank()) {
            _uiState.update { it.copy(error = stringResource(R.string.enter_username)) }
            return
        }
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.length !in 3..20 || !normalizedUsername.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$"))) {
            _uiState.update { it.copy(error = stringResource(R.string.str_108)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signUpWithEmail(normalizedEmail, password, displayName.trim(), normalizedUsername)
                if (uid != null) {
                    // Create the Firestore profile with the username
                    communityRepository.upsertProfile(
                        username = normalizedUsername,
                        bio = "",
                        isPublic = true,
                        displayName = displayName.trim()
                    )
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = stringResource(R.string.auth_error_signup_failed)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(error.reason)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
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
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = stringResource(R.string.str_335)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(error.reason)) }
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
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = stringResource(R.string.str_334)) }
                }
            } catch (error: AccountMergeRequiredException) {
                _uiState.update { it.copy(isLoading = false, error = accountMergeMessage(error.reason)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    /**
     * After social sign-in, check if the user already has a Firestore profile.
     * If not, create one using the provider's display name as both displayName and username.
     */
    private suspend fun ensureProfileExists(uid: String) {
        val existing = communityRepository.getCurrentProfile()
        if (existing == null || existing.username.isBlank()) {
            val firebaseUser = sessionManager.currentUser()
            val providerName = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: ""
            // Generate a username from provider name: lowercase, replace spaces with underscores, keep alphanumeric + underscores
            val generatedUsername = providerName.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .trim()
                .replace(Regex("\\s+"), "_")
                .take(20)
                .ifBlank { "user_${uid.takeLast(6)}" }
            try {
                communityRepository.upsertProfile(
                    username = generatedUsername,
                    bio = "",
                    isPublic = true,
                    displayName = providerName
                )
            } catch (_: Exception) {
                // Username might be taken — append random suffix
                val fallback = "${generatedUsername}_${(1000..9999).random()}"
                try {
                    communityRepository.upsertProfile(
                        username = fallback,
                        bio = "",
                        isPublic = true,
                        displayName = providerName
                    )
                } catch (_: Exception) { /* Profile creation failed silently */ }
            }
        }
    }

    fun sendPasswordReset(email: String) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            _uiState.update { it.copy(error = stringResource(R.string.enter_email)) }
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            _uiState.update { it.copy(error = stringResource(R.string.invalid_email)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, passwordResetSent = false) }
            try {
                sessionManager.sendPasswordResetEmail(normalizedEmail)
                _uiState.update { it.copy(isLoading = false, passwordResetSent = true, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e), passwordResetSent = false) }
            }
        }
    }

    fun clearPasswordResetSent() {
        _uiState.update { it.copy(passwordResetSent = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun mapAuthError(error: Exception): String = firebaseAuthErrorMessage(error)
}
