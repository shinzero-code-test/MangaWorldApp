package com.exapps.mangaworld.presentation.cloud

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudSyncUiState(
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager
) : ViewModel() {
    private val _state = MutableStateFlow(CloudSyncUiState())
    val state: StateFlow<CloudSyncUiState> = _state.asStateFlow()
    val currentUser = sessionManager.authState.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, sessionManager.currentUser())

    fun googleSignInIntent(): Intent = sessionManager.googleSignInClient().signInIntent
    fun hasGoogleSignIn(): Boolean = sessionManager.hasGoogleClientId()

    fun signInWithGoogleIdToken(idToken: String?) {
        if (idToken.isNullOrBlank()) {
            _state.value = CloudSyncUiState(errorMessage = "تعذر التقاط رمز Google")
            return
        }
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ تسجيل الدخول...")
            runCatching {
                sessionManager.signInWithGoogleIdToken(idToken)
                syncManager.pushLocalSnapshot()
                remoteConfigManager.refresh()
            }.onSuccess {
                _state.value = CloudSyncUiState(statusMessage = "تم تسجيل الدخول والمزامنة")
            }.onFailure { e ->
                _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل تسجيل الدخول عبر Google")
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ تسجيل الدخول...")
            runCatching {
                sessionManager.signInWithEmail(email, password)
                syncManager.pushLocalSnapshot()
            }.onSuccess {
                _state.value = CloudSyncUiState(statusMessage = "تم تسجيل الدخول بنجاح")
            }.onFailure { e ->
                _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل تسجيل الدخول")
            }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ إنشاء الحساب...")
            runCatching {
                sessionManager.signUpWithEmail(email, password)
                syncManager.pushLocalSnapshot()
            }.onSuccess {
                _state.value = CloudSyncUiState(statusMessage = "تم إنشاء الحساب والمزامنة")
            }.onFailure { e ->
                _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل إنشاء الحساب")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ رفع البيانات...")
            runCatching { syncManager.pushLocalSnapshot() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "تم رفع البيانات إلى السحابة") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل رفع البيانات") }
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ استرجاع البيانات...")
            runCatching { syncManager.pullRemoteSnapshot() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "تمت استعادة البيانات السحابية") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل استرجاع البيانات") }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "جارٍ تسجيل الخروج...")
            runCatching { sessionManager.signOut() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "تم تسجيل الخروج") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "فشل تسجيل الخروج") }
        }
    }

    fun clearMessages() {
        _state.value = CloudSyncUiState()
    }
}

@Composable
fun CloudSyncScreen(
    onBack: () -> Unit,
    viewModel: CloudSyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = runCatching { task.getResult(java.lang.Exception::class.java) }.getOrNull()
            viewModel.signInWithGoogleIdToken(account?.idToken)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MangaColors.OnSurface)
            }
            Text("السحابة والمزامنة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
            IconButton(onClick = viewModel::clearMessages) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = MangaColors.OnSurface)
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الحساب الحالي", fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
                Text(
                    when {
                        user == null -> "غير متصل"
                        user?.isAnonymous == true -> "ضيف محلي (${user?.uid?.takeLast(6)})"
                        else -> user?.email ?: user?.uid.orEmpty()
                    },
                    color = MangaColors.OnSurfaceVariant
                )
                state.statusMessage?.let { Text(it, color = MangaColors.Green) }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) CircularProgressIndicator(color = MangaColors.Primary)
            }
        }

        if (user?.isAnonymous != false) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("تسجيل الدخول أو إنشاء حساب", fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
                    OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("البريد الإلكتروني") })
                    OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.signInWithEmail(email, password) }) { Icon(Icons.Filled.Login, null); Spacer(Modifier.height(0.dp)); Text("دخول") }
                        Button(onClick = { viewModel.signUpWithEmail(email, password) }) { Text("إنشاء حساب") }
                    }
                    Button(
                        onClick = { googleLauncher.launch(viewModel.googleSignInIntent()) },
                        enabled = viewModel.hasGoogleSignIn(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (viewModel.hasGoogleSignIn()) "متابعة باستخدام Google" else "Google غير مهيأ بعد")
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("المزامنة السحابية", fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
                Button(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth(), enabled = user != null && !state.busy) {
                    Icon(Icons.Filled.CloudSync, null)
                    Text("رفع البيانات الآن")
                }
                Button(onClick = viewModel::restoreFromCloud, modifier = Modifier.fillMaxWidth(), enabled = user != null && !state.busy) {
                    Icon(Icons.Filled.CloudDownload, null)
                    Text("استرجاع البيانات من السحابة")
                }
                if (user?.isAnonymous == false) {
                    Button(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Icon(Icons.Filled.Logout, null)
                        Text("تسجيل الخروج")
                    }
                }
            }
        }
    }
}
