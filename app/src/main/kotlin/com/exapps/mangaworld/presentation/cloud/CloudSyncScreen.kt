package com.exapps.mangaworld.presentation.cloud
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.domain.model.CloudRestorePreview
import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class CloudSyncUiState(
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val restorePreview: CloudRestorePreview? = null
)

@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    private val sessionManager: FirebaseSessionManager,
    private val syncManager: FirebaseSyncManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val communityRepository: CommunityRepository,
    private val analyticsManager: FirebaseAnalyticsManager
) : ViewModel() {
    private val _state = MutableStateFlow(CloudSyncUiState())
    val state: StateFlow<CloudSyncUiState> = _state.asStateFlow()
    val currentUser = sessionManager.authState.stateIn(viewModelScope, SharingStarted.Eagerly, sessionManager.currentUser())
    val profile = flow { emit(communityRepository.getCurrentProfile()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val notifications = communityRepository.observeNotifications()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun googleSignInIntent(): Intent = sessionManager.googleSignInClient().signInIntent
    fun hasGoogleSignIn(): Boolean = sessionManager.hasGoogleClientId()

    fun signInWithGoogleIdToken(idToken: String?) {
        if (idToken.isNullOrBlank()) { _state.value = CloudSyncUiState(errorMessage = "Failed to get Google token"); return }
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Signing in...")
            runCatching { sessionManager.signInWithGoogleIdToken(idToken); syncManager.pushLocalSnapshot(); remoteConfigManager.refresh() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Signed in and synced") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Google sign-in failed") }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Signing in...")
            runCatching { sessionManager.signInWithEmail(email, password); syncManager.pushLocalSnapshot() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Signed in successfully") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Sign-in failed") }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Creating account...")
            runCatching { sessionManager.signUpWithEmail(email, password, displayName = "", username = ""); syncManager.pushLocalSnapshot() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Account created and synced") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Account creation failed") }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Uploading data...")
            runCatching { syncManager.pushLocalSnapshot() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Data uploaded to cloud") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Upload failed") }
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Fetching cloud data...")
            runCatching { syncManager.previewRemoteSnapshot() }
                .onSuccess { preview -> _state.value = CloudSyncUiState(restorePreview = preview, statusMessage = "Review conflicts before restoring") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Restore preview failed") }
        }
    }

    fun applyRestore(strategy: CloudRestoreStrategy) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Applying restore...")
            runCatching { syncManager.applyRemoteRestore(strategy) }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Restore applied successfully") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Restore failed") }
        }
    }

    fun saveProfile(username: String, bio: String, isPublic: Boolean) {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Saving profile...")
            val currentProfile = profile.value
            runCatching { communityRepository.upsertProfile(username, bio, isPublic, currentProfile?.avatarUrl, displayName = currentProfile?.displayName ?: "") }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Profile saved") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Profile save failed") }
        }
    }

    fun markNotificationRead(id: String) { viewModelScope.launch { runCatching { communityRepository.markNotificationRead(id) } } }

    fun signOut() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState(busy = true, statusMessage = "Signing out...")
            runCatching { sessionManager.signOut() }
                .onSuccess { _state.value = CloudSyncUiState(statusMessage = "Signed out") }
                .onFailure { e -> _state.value = CloudSyncUiState(errorMessage = e.message ?: "Sign-out failed") }
        }
    }

    fun clearMessages() { _state.value = CloudSyncUiState() }
    fun onScreenViewed() { analyticsManager.logScreen("cloud_sync") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    onBack: () -> Unit,
    viewModel: CloudSyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember(profile?.username) { mutableStateOf(profile?.username.orEmpty()) }
    var bio by remember(profile?.bio) { mutableStateOf(profile?.bio.orEmpty()) }
    var isPublic by remember(profile?.isPublic) { mutableStateOf(profile?.isPublic ?: true) }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = runCatching { task.getResult(java.lang.Exception::class.java) }.getOrNull()
            viewModel.signInWithGoogleIdToken(account?.idToken)
        }
    }

    LaunchedEffect(Unit) { viewModel.onScreenViewed() }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.str_159), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearMessages) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.update), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Status banner
            AnimatedVisibility(visible = state.statusMessage != null || state.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.errorMessage != null)
                            MangaColors.Error.copy(alpha = 0.15f)
                        else MangaColors.Green.copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MangaColors.Primary
                            )
                        } else {
                            Icon(
                                if (state.errorMessage != null) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                                null,
                                tint = if (state.errorMessage != null) MangaColors.Error else MangaColors.Green,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            state.errorMessage ?: state.statusMessage ?: "",
                            color = if (state.errorMessage != null) MangaColors.Error else MangaColors.OnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.errorMessage != null && !state.busy) {
                            TextButton(onClick = { viewModel.clearMessages() }) {
                                Text(stringResource(R.string.close), color = MangaColors.Error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Account card
            AccountCard(
                user = user,
                isSigningIn = state.busy,
                email = email,
                password = password,
                onEmailChange = { email = it },
                onPasswordChange = { password = it },
                onEmailLogin = { viewModel.signInWithEmail(email, password) },
                onGoogleLogin = { googleLauncher.launch(viewModel.googleSignInIntent()) },
                hasGoogleSignIn = viewModel.hasGoogleSignIn(),
                onSignOut = viewModel::signOut
            )

            // Profile card
            ProfileCard(
                username = username,
                bio = bio,
                isPublic = isPublic,
                badgeLabel = profile?.badgeLabel,
                isSaving = state.busy,
                onUsernameChange = { username = it },
                onBioChange = { bio = it },
                onPublicChange = { isPublic = it },
                onSave = { viewModel.saveProfile(username, bio, isPublic) }
            )

            // Sync actions
            SyncActionsCard(
                isSyncing = state.busy,
                isSignedIn = user != null,
                onPush = viewModel::syncNow,
                onPull = viewModel::restoreFromCloud,
                restorePreview = state.restorePreview,
                onApplyRestore = viewModel::applyRestore
            )

            // Notifications
            if (notifications.isNotEmpty()) {
                NotificationsCard(
                    notifications = notifications.take(5),
                    onMarkRead = viewModel::markNotificationRead
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountCard(
    user: com.google.firebase.auth.FirebaseUser?,
    isSigningIn: Boolean,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onEmailLogin: () -> Unit,
    onGoogleLogin: () -> Unit,
    hasGoogleSignIn: Boolean,
    onSignOut: () -> Unit
) {
    val isSignedIn = user != null && !user.isAnonymous

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header with status
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MangaColors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Person, null, tint = MangaColors.Primary, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.account), fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
                    Text(
                        when {
                            user == null -> stringResource(R.string.offline)
                            user.isAnonymous -> stringResource(R.string.local_guest)
                            else -> user.email ?: stringResource(R.string.online)
                        },
                        color = MangaColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Status dot
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    if (isSignedIn) MangaColors.Green else MangaColors.Muted
                ))
            }

            if (isSignedIn) {
                // Signed in — show sign out
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSigningIn,
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = !isSigningIn)
                ) {
                    Icon(Icons.Filled.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_sign_out))
                }
            } else {
                // Not signed in — show login form
                OutlinedTextField(
                    value = email, onValueChange = onEmailChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.settings_email)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = password, onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.auth_password_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Button(
                    onClick = onEmailLogin,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = email.isNotBlank() && password.isNotBlank() && !isSigningIn
                ) {
                    Text(stringResource(R.string.auth_login))
                }
                if (hasGoogleSignIn) {
                    OutlinedButton(
                        onClick = onGoogleLogin,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isSigningIn
                    ) {
                        Text(stringResource(R.string.continue_google_alt))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    username: String,
    bio: String,
    isPublic: Boolean,
    badgeLabel: String?,
    isSaving: Boolean,
    onUsernameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MangaColors.Cyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Badge, null, tint = MangaColors.Cyan, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.more_profile), fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
                    if (!badgeLabel.isNullOrBlank()) {
                        Text(stringResource(R.string.fmt_048, badgeLabel), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(
                value = username, onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.profile_username)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = bio, onValueChange = onBioChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.short_bio)) },
                maxLines = 3,
                shape = RoundedCornerShape(10.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isPublic, onCheckedChange = onPublicChange)
                Spacer(Modifier.width(8.dp))
                Text("public profile", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                enabled = username.isNotBlank() && !isSaving
            ) {
                Text(stringResource(R.string.profile_save))
            }
        }
    }
}

@Composable
private fun SyncActionsCard(
    isSyncing: Boolean,
    isSignedIn: Boolean,
    onPush: () -> Unit,
    onPull: () -> Unit,
    restorePreview: com.exapps.mangaworld.domain.model.CloudRestorePreview?,
    onApplyRestore: (CloudRestoreStrategy) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MangaColors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CloudSync, null, tint = MangaColors.Primary, modifier = Modifier.size(24.dp))
                }
                Text(stringResource(R.string.more_sync), fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPush,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = isSignedIn && !isSyncing
                ) {
                    Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.upload))
                }
                OutlinedButton(
                    onClick = onPull,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = isSignedIn && !isSyncing
                ) {
                    Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.restore))
                }
            }

            if (!isSignedIn) {
                Text(stringResource(R.string.str_290), color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
            }

            // Restore preview
            restorePreview?.let { preview ->
                HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.15f))
                Text(stringResource(R.string.restore_preview), fontWeight = FontWeight.SemiBold, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStat(stringResource(R.string.local), preview.localFavorites, MangaColors.Cyan)
                    SyncStat(stringResource(R.string.cloud_alt), preview.remoteFavorites, MangaColors.Primary)
                }
                Text(stringResource(R.string.fmt_063, preview.localFavorites, preview.remoteFavorites), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.fmt_051, preview.localHistory, preview.remoteHistory), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CloudRestoreStrategy.entries.forEach { strategy ->
                        FilterChip(
                            selected = preview.suggestedStrategy == strategy,
                            onClick = { onApplyRestore(strategy) },
                            label = { Text(when(strategy) {
                                CloudRestoreStrategy.MERGE -> stringResource(R.string.merge)
                                CloudRestoreStrategy.REMOTE_OVERWRITE -> stringResource(R.string.str_002)
                                CloudRestoreStrategy.KEEP_LOCAL -> stringResource(R.string.local_storage)
                            }, fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NotificationsCard(
    notifications: List<com.exapps.mangaworld.domain.model.CommunityNotification>,
    onMarkRead: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MangaColors.Yellow.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.NotificationsActive, null, tint = MangaColors.Yellow, modifier = Modifier.size(24.dp))
                }
                Text(stringResource(R.string.profile_community_notifications), fontWeight = FontWeight.Bold, color = MangaColors.OnSurface)
            }

            notifications.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onMarkRead(item.id) },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.read) MangaColors.Surface else MangaColors.GlowPurple
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(item.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 2)
