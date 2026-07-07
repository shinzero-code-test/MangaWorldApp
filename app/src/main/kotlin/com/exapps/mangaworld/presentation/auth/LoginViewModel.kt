package com.exapps.mangaworld.presentation.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@Immutable
data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSignedIn: Boolean = false,
    val email: String = "",
    val password: String = "",
    val passwordResetSent: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val sessionManager: FirebaseSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Check if already signed in
        viewModelScope.launch {
            sessionManager.authState.collect { user ->
                _uiState.update { it.copy(isSignedIn = user != null && !user.isAnonymous) }
            }
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun signInWithEmail() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "أدخل البريد الإلكتروني وكلمة المرور") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signInWithEmail(state.email.trim(), state.password)
                if (uid != null) {
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "فشل تسجيل الدخول. تحقق من البيانات.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "أدخل البريد الإلكتروني وكلمة المرور") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(error = "كلمة المرور يجب أن تكون 6 أحرف على الأقل") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val uid = sessionManager.signUpWithEmail(email.trim(), password)
                if (uid != null) {
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "فشل إنشاء الحساب.") }
                }
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
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "فشل تسجيل الدخول بـ Google.") }
                }
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
                    _uiState.update { it.copy(isLoading = false, isSignedIn = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "فشل تسجيل الدخول بـ Facebook.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(error = "أدخل البريد الإلكتروني") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
                _uiState.update { it.copy(
                    isLoading = false,
                    passwordResetSent = true,
                    error = null
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = mapAuthError(e)) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun mapAuthError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("password is invalid", true) || msg.contains("wrong password", true) ||
            msg.contains("INVALID_LOGIN_CREDENTIALS", true) -> "بيانات الدخول غير صحيحة"
            msg.contains("no user record", true) || msg.contains("user not found", true) ->
                "لا يوجد حساب بهذا البريد الإلكتروني"
            msg.contains("email address is already", true) || msg.contains("already in use", true) ->
                "هذا البريد الإلكتروني مستخدم بالفعل"
            msg.contains("weak password", true) || msg.contains("should be at least", true) ->
                "كلمة المرور ضعيفة. استخدم 6 أحرف على الأقل"
            msg.contains("invalid email", true) || msg.contains("malformed", true) ->
                "البريد الإلكتروني غير صالح"
            msg.contains("network", true) || msg.contains("timeout", true) ->
                "تحقق من اتصال الإنترنت"
            msg.contains("too many requests", true) || msg.contains("quota", true) ->
                "محاولات كثيرة. حاول مرة أخرى بعد قليل"
            msg.contains("NETWORK_ERROR", true) ->
                "خطأ في الشبكة. تحقق من اتصال الإنترنت"
            else -> "حدث خطأ. حاول مرة أخرى"
        }
    }
}
