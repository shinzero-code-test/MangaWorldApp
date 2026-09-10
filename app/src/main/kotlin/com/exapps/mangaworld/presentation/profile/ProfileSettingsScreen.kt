package com.exapps.mangaworld.presentation.profile
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.firebase.CloudinaryUploader
import com.exapps.mangaworld.core.firebase.AccountMergeRequiredException
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.ProviderManagementRequiresSignInException
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.UserFollow
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SecurityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.presentation.auth.accountMergeMessage
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.AggregateSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─── ViewModel ──────────────────────────────────────────────────────────────

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager,
    private val securityRepository: SecurityRepository,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readChapterDao: ReadChapterDao,
    private val cloudinaryUploader: CloudinaryUploader,
    private val auth: com.google.firebase.auth.FirebaseAuth,
    private val firestore: com.google.firebase.firestore.FirebaseFirestore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _userEmail = MutableStateFlow<String?>(auth.currentUser?.email)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val profile = kotlinx.coroutines.flow.flow { emit(communityRepository.getCurrentProfile()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appSettings = settingsRepository.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // Social lists (real data for the following/followers dialogs).
    private val _following = MutableStateFlow<List<UserFollow>>(emptyList())
    val following: StateFlow<List<UserFollow>> = _following.asStateFlow()
    private val _followers = MutableStateFlow<List<UserFollow>>(emptyList())
    val followers: StateFlow<List<UserFollow>> = _followers.asStateFlow()

    // Security centre (real data — login logs, devices, sessions).
    val loginLogs = securityRepository.observeLoginLogs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val devices = securityRepository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions = securityRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _securityBusy = MutableStateFlow(false)
    val securityBusy: StateFlow<Boolean> = _securityBusy.asStateFlow()
    private val _securityError = MutableStateFlow<String?>(null)
    val securityError: StateFlow<String?> = _securityError.asStateFlow()

    // Lists export/import result (surfaced as a one-shot message).
    private val _listsMessage = MutableStateFlow<String?>(null)
    val listsMessage: StateFlow<String?> = _listsMessage.asStateFlow()

    private val _favoriteCount = MutableStateFlow(0)
    val favoriteCount: StateFlow<Int> = _favoriteCount.asStateFlow()
    private val _historyCount = MutableStateFlow(0)
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()
    private val _readCount = MutableStateFlow(0)
    val readCount: StateFlow<Int> = _readCount.asStateFlow()

    // Social counts
    private val _followingCount = MutableStateFlow(0)
    val followingCount: StateFlow<Int> = _followingCount.asStateFlow()
    private val _followersCount = MutableStateFlow(0)
    val followersCount: StateFlow<Int> = _followersCount.asStateFlow()
    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    // Preferences
    private val _favoriteGenres = MutableStateFlow<List<String>>(emptyList())
    val favoriteGenres: StateFlow<List<String>> = _favoriteGenres.asStateFlow()
    private val _blockedUsers = MutableStateFlow<Set<String>>(emptySet())
    val blockedUsers: StateFlow<Set<String>> = _blockedUsers.asStateFlow()
    private val _commentsCount = MutableStateFlow(0)
    val commentsCount: StateFlow<Int> = _commentsCount.asStateFlow()
    private val _reviewsCount = MutableStateFlow(0)
    val reviewsCount: StateFlow<Int> = _reviewsCount.asStateFlow()

    var avatarUri by mutableStateOf<Uri?>(null); private set

    private val _linkedProviderIds = MutableStateFlow(sessionManager.linkedProviderIds())
    val linkedProviderIds: StateFlow<Set<String>> = _linkedProviderIds.asStateFlow()
    private val _providerLinkError = MutableStateFlow<String?>(null)
    val providerLinkError: StateFlow<String?> = _providerLinkError.asStateFlow()

    init {
        viewModelScope.launch {
            profile.first { it != null }
            _isLoading.value = false
            _favoriteCount.value = favoriteDao.getFavoritesList().size
            _historyCount.value = historyDao.getAll().size
            _readCount.value = readChapterDao.getTotalReadCount()
            // Social counts + lists (real data for the section + dialogs).
            val uid = sessionManager.currentUserId()
            if (uid != null) {
                _followingCount.value = communityRepository.getFollowingCount(uid)
                _followersCount.value = communityRepository.getFollowersCount(uid)
                // Comments & reviews counts via Firestore aggregate queries
                try {
                    _commentsCount.value = firestore.collectionGroup("comments")
                        .whereEqualTo("authorUid", uid).count().get(AggregateSource.SERVER).await().count.toInt()
                    _reviewsCount.value = firestore.collectionGroup("reviews")
                        .whereEqualTo("authorUid", uid).count().get(AggregateSource.SERVER).await().count.toInt()
                } catch (_: Exception) {}
            }
        }
        // Keep this device's session row fresh (last-seen for the sessions list).
        viewModelScope.launch { runCatching { securityRepository.ensureCurrentSession() } }
        // Follow lists for the dialogs.
        viewModelScope.launch {
            val uid = sessionManager.currentUserId() ?: return@launch
            launch { communityRepository.observeFollowing(uid).collect { _following.value = it } }
            launch { communityRepository.observeFollowers(uid).collect { _followers.value = it } }
        }
        // Observe blocked users
        viewModelScope.launch {
            communityRepository.getBlockedUsers().collect { _blockedUsers.value = it }
        }
        viewModelScope.launch {
            settingsRepository.getFavoriteGenres().collect { _favoriteGenres.value = it }
        }
        viewModelScope.launch {
            sessionManager.authState.collect { _linkedProviderIds.value = sessionManager.linkedProviderIds() }
        }
    }

    fun updateAvatarUri(uri: Uri) { avatarUri = uri }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            val current = communityRepository.getCurrentProfile()
            val result = cloudinaryUploader.uploadImage(uri, assetType = "avatar")
            if (result != null) {
                communityRepository.upsertProfile(
                    username = current?.username ?: "",
                    bio = current?.bio ?: "",
                    isPublic = current?.isPublic ?: true,
                    avatarUrl = result.url,
                    bannerUrl = current?.bannerUrl,
                    displayName = current?.displayName ?: ""
                )
                val oldUrl = current?.avatarUrl
                if (oldUrl != null) {
                    val oldId = cloudinaryUploader.extractPublicId(oldUrl)
                    if (oldId != null) cloudinaryUploader.deleteImage(oldId)
                }
                avatarUri = null
            }
        }
    }

    /**
     * Birthday is pass-through (null clears it): the edit dialog always sends
     * the field state initialized from the profile, so bio-only saves keep it.
     */
    fun updateProfile(
        username: String,
        bio: String,
        displayName: String = "",
        location: String = "",
        birthday: Long? = null
    ) {
        viewModelScope.launch {
            val c = communityRepository.getCurrentProfile()
            communityRepository.upsertProfile(
                username = username.ifBlank { c?.username ?: "" },
                bio = bio,
                isPublic = c?.isPublic ?: true,
                avatarUrl = c?.avatarUrl,
                bannerUrl = c?.bannerUrl,
                displayName = displayName.ifBlank { c?.displayName ?: "" },
                location = location.ifBlank { c?.location ?: "" },
                birthday = birthday
            )
        }
    }

    fun updatePrivacy(showLists: Boolean, showActivity: Boolean, isPublic: Boolean) {
        viewModelScope.launch {
            val c = communityRepository.getCurrentProfile()
            communityRepository.upsertProfile(
                username = c?.username ?: "",
                bio = c?.bio ?: "",
                isPublic = isPublic,
                avatarUrl = c?.avatarUrl,
                bannerUrl = c?.bannerUrl,
                displayName = c?.displayName ?: ""
            )
            communityRepository.updateProfilePrivacy(showLists, showActivity)
        }
    }

    fun toggleNotifications(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) } }
    fun toggleNotifyComments(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNotifyCommentsEnabled(enabled) } }
    fun toggleNotifyLikes(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNotifyLikesEnabled(enabled) } }
    fun toggleNotifyFollowers(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNotifyFollowersEnabled(enabled) } }
    fun toggleBiometric(enabled: Boolean) { viewModelScope.launch { settingsRepository.setBiometricLock(enabled) } }
    fun toggleShowLibraryPublic(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowLibraryPublic(enabled)
            val c = communityRepository.getCurrentProfile()
            communityRepository.updateProfilePrivacy(
                c?.showListsPublic ?: true,
                c?.showActivityPublic ?: true,
                enabled
            )
        }
    }
    fun signOut() {
        viewModelScope.launch {
            try {
                sessionManager.signOut()
            } finally {
                _userEmail.value = null
            }
        }
    }

    // ─── Security centre actions ──────────────────────────────────────────

    fun deleteLoginLog(id: String) {
        viewModelScope.launch { runCatching { securityRepository.deleteLoginLog(id) } }
    }

    fun clearLoginLogs() {
        viewModelScope.launch { runCatching { securityRepository.clearLoginLogs() } }
    }

    fun removeDevice(id: String) {
        viewModelScope.launch { runCatching { securityRepository.removeDevice(id) } }
    }

    fun revokeSession(id: String) {
        viewModelScope.launch {
            _securityBusy.value = true
            _securityError.value = null
            runCatching { securityRepository.revokeSession(id) }
                .onFailure { _securityError.value = context.getString(R.string.settings_security_action_failed) }
            _securityBusy.value = false
        }
    }

    /** True "sign out everywhere": server revokes tokens, then we drop local state. */
    fun signOutAllDevices(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            _securityBusy.value = true
            _securityError.value = null
            val result = runCatching { securityRepository.signOutAllDevices() }
            _securityBusy.value = false
            result
                .onSuccess {
                    _userEmail.value = null
                    onSignedOut()
                }
                .onFailure { _securityError.value = context.getString(R.string.settings_security_action_failed) }
        }
    }

    fun clearSecurityError() { _securityError.value = null }

    // ─── Custom lists export/import ───────────────────────────────────────

    /** Serializes all user lists (+ items) to a versioned JSON document. */
    suspend fun exportListsJson(): String {
        val lists = communityRepository.observeUserLists().first()
        val arr = org.json.JSONArray()
        lists.take(MAX_EXPORT_LISTS).forEach { list ->
            val items = communityRepository.observeListItems(list.id).first()
            val itemsArr = org.json.JSONArray()
            items.take(MAX_EXPORT_ITEMS).forEach { item ->
                itemsArr.put(
                    org.json.JSONObject()
                        .put("mangaId", item.mangaId.take(MAX_TEXT))
                        .put("sourceId", item.sourceId.take(MAX_TEXT_SHORT))
                        .put("slug", item.slug.take(MAX_TEXT))
                        .put("title", item.title.take(MAX_TEXT_TITLE))
                        .put("coverUrl", item.coverUrl.take(MAX_URL))
                        .put("rating", item.rating)
                        .put("genres", org.json.JSONArray(item.genres.take(MAX_GENRES)))
                )
            }
            arr.put(
                org.json.JSONObject()
                    .put("name", list.name.take(MAX_TEXT_TITLE))
                    .put("description", list.description.take(MAX_TEXT_DESC))
                    .put("coverUrl", list.coverUrl.take(MAX_URL))
                    .put("rating", list.rating)
                    .put("genres", org.json.JSONArray(list.genres.take(MAX_GENRES)))
                    .put("isPublic", false)
                    .put("items", itemsArr)
            )
        }
        return org.json.JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("lists", arr)
            .toString()
    }

    /** Imports a document produced by [exportListsJson]; returns a user message. */
    fun importListsJson(raw: String) {
        viewModelScope.launch {
            _listsMessage.value = try {
                val root = org.json.JSONObject(raw)
                require(root.optInt("version", 0) == 1) { "version" }
                val lists = root.optJSONArray("lists") ?: org.json.JSONArray()
                require(lists.length() <= MAX_EXPORT_LISTS) { "lists" }
                var listCount = 0
                var itemCount = 0
                for (i in 0 until lists.length()) {
                    val obj = lists.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim().take(MAX_TEXT_TITLE)
                    if (name.isBlank()) continue
                    val items = obj.optJSONArray("items") ?: org.json.JSONArray()
                    require(items.length() <= MAX_EXPORT_ITEMS) { "items" }
                    val newId = communityRepository.createOrUpdateList(
                        listId = null,
                        name = name,
                        description = obj.optString("description").trim().take(MAX_TEXT_DESC),
                        coverUrl = obj.optString("coverUrl").take(MAX_URL),
                        rating = obj.optDouble("rating", 0.0).toFloat().coerceIn(0f, 5f),
                        genres = obj.optJSONArray("genres")?.let { g ->
                            (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() }.take(MAX_GENRES)
                        } ?: emptyList(),
                        isPublic = false
                    )
                    listCount++
                    for (j in 0 until items.length()) {
                        val it = items.optJSONObject(j) ?: continue
                        val mangaId = it.optString("mangaId").trim()
                        val slug = it.optString("slug").trim()
                        if (mangaId.isBlank() || slug.isBlank()) continue
                        // Custom lists are online-manga only (Phase 1, item 7a).
                        val sourceId = it.optString("sourceId").trim()
                        if (sourceId.isBlank() || com.exapps.mangaworld.domain.model.MangaSource.isLocalSource(sourceId)) continue
                        communityRepository.addMangaToList(
                            newId,
                            com.exapps.mangaworld.domain.model.CustomUserListItem(
                                mangaId = mangaId.take(MAX_TEXT),
                                sourceId = sourceId.take(MAX_TEXT_SHORT),
                                slug = slug.take(MAX_TEXT),
                                title = it.optString("title").trim().take(MAX_TEXT_TITLE),
                                coverUrl = it.optString("coverUrl").take(MAX_URL),
                                rating = it.optDouble("rating", 0.0).toFloat().coerceIn(0f, 5f),
                                genres = it.optJSONArray("genres")?.let { g ->
                                    (0 until g.length()).map { g.optString(it) }.filter { s -> s.isNotBlank() }.take(MAX_GENRES)
                                } ?: emptyList()
                            )
                        )
                        itemCount++
                    }
                }
                context.getString(R.string.settings_lists_imported, listCount, itemCount)
            } catch (_: Exception) {
                context.getString(R.string.settings_lists_import_failed)
            }
        }
    }

    fun clearListsMessage() { _listsMessage.value = null }
    fun setListsMessage(message: String) { _listsMessage.value = message }

    private companion object {
        const val MAX_EXPORT_LISTS = 50
        const val MAX_EXPORT_ITEMS = 500
        const val MAX_GENRES = 20
        const val MAX_TEXT = 256
        const val MAX_TEXT_SHORT = 64
        const val MAX_TEXT_TITLE = 200
        const val MAX_TEXT_DESC = 500
        const val MAX_URL = 2048
    }

    fun deleteAccount() {
        viewModelScope.launch {
            try {
                val user = auth.currentUser
                if (user != null) {
                    user.delete().await()
                }
            } catch (_: Exception) {
                // Account deletion may require recent authentication
            } finally {
                try { sessionManager.signOut() } catch (_: Exception) {}
                _userEmail.value = null
            }
        }
    }

    fun blockUser(uid: String) {
        viewModelScope.launch { communityRepository.blockUser(uid) }
    }

    fun unblockUser(uid: String) {
        viewModelScope.launch { communityRepository.unblockUser(uid) }
    }

    fun setFavoriteGenres(genres: List<String>) {
        _favoriteGenres.value = genres
        viewModelScope.launch { settingsRepository.setFavoriteGenres(genres) }
    }

    fun googleSignInIntent() = sessionManager.googleSignInClient().signInIntent

    fun linkGoogle(idToken: String) {
        viewModelScope.launch { linkProvider { sessionManager.linkGoogle(idToken) } }
    }

    fun linkFacebook(accessToken: String) {
        viewModelScope.launch { linkProvider { sessionManager.linkFacebook(accessToken) } }
    }

    fun unlinkProvider(providerId: String) {
        viewModelScope.launch { linkProvider { sessionManager.unlinkProvider(providerId) } }
    }

    fun onProviderLinkError(message: String) {
        _providerLinkError.value = message
    }

    private suspend fun linkProvider(action: suspend () -> Unit) {
        _providerLinkError.value = null
        try {
            action()
        } catch (error: AccountMergeRequiredException) {
            _providerLinkError.value = accountMergeMessage(context, error.reason)
        } catch (error: ProviderManagementRequiresSignInException) {
            _providerLinkError.value = if (error.isGuestSession) {
                context.getString(R.string.settings_provider_guest_error)
            } else {
                context.getString(R.string.settings_provider_sign_in_error)
            }
        } catch (error: IllegalArgumentException) {
            _providerLinkError.value = error.message ?: context.getString(R.string.cannot_make_change)
        } catch (_: Exception) {
            _providerLinkError.value = context.getString(R.string.settings_provider_link_error)
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatJoinDate(context: android.content.Context, timestamp: Long): String {
    if (timestamp == 0L) return context.getString(R.string.unknown)
    return try {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (_: Exception) { context.getString(R.string.unknown) }
}

// ─── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit = {},
    onOpenCloudSync: () -> Unit,
    onOpenSources: () -> Unit,
    setFacebookCallbackManager: (com.facebook.CallbackManager) -> Unit,
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val readCount by viewModel.readCount.collectAsStateWithLifecycle()
    val followingCount by viewModel.followingCount.collectAsStateWithLifecycle()
    val followersCount by viewModel.followersCount.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val followers by viewModel.followers.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsers.collectAsStateWithLifecycle()
    val commentsCount by viewModel.commentsCount.collectAsStateWithLifecycle()
    val reviewsCount by viewModel.reviewsCount.collectAsStateWithLifecycle()
    val linkedProviderIds by viewModel.linkedProviderIds.collectAsStateWithLifecycle()
    val providerLinkError by viewModel.providerLinkError.collectAsStateWithLifecycle()
    val loginLogs by viewModel.loginLogs.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val securityBusy by viewModel.securityBusy.collectAsStateWithLifecycle()
    val securityError by viewModel.securityError.collectAsStateWithLifecycle()
    val listsMessage by viewModel.listsMessage.collectAsStateWithLifecycle()
    val avatarUri = viewModel.avatarUri

    var expandedSection by remember { mutableStateOf<String?>(null) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showSignOutAllConfirm by remember { mutableStateOf(false) }
    var showBlockedUsers by remember { mutableStateOf(false) }
    var showFollowingList by remember { mutableStateOf(false) }
    var showFollowersList by remember { mutableStateOf(false) }
    var showLoginLogs by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.uploadAvatar(it) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val googleLinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data).result
            val idToken = account?.idToken
            if (idToken != null) {
                viewModel.linkGoogle(idToken)
            }
        } catch (e: Exception) {
            viewModel.onProviderLinkError(context.getString(R.string.fmt_078, e.localizedMessage ?: context.getString(R.string.unknown_error)))
        }
    }
    val facebookCallbackManager = remember { com.facebook.CallbackManager.Factory.create() }
    DisposableEffect(facebookCallbackManager) {
        val callback = object : com.facebook.FacebookCallback<com.facebook.login.LoginResult> {
            override fun onSuccess(result: com.facebook.login.LoginResult) {
                viewModel.linkFacebook(result.accessToken.token)
            }
            override fun onCancel() = Unit
            override fun onError(error: com.facebook.FacebookException) = Unit
        }
        val loginManager = com.facebook.login.LoginManager.getInstance()
        loginManager.registerCallback(facebookCallbackManager, callback)
        setFacebookCallbackManager(facebookCallbackManager)
        onDispose { loginManager.unregisterCallback(facebookCallbackManager) }
    }

    // Lists export/import via Storage Access Framework (no storage permission).
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = viewModel.exportListsJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    } ?: error("no stream")
                    viewModel.setListsMessage(context.getString(R.string.settings_lists_exported))
                }.onFailure {
                    viewModel.setListsMessage(context.getString(R.string.settings_lists_import_failed))
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.onSuccess { raw ->
                if (raw != null) viewModel.importListsJson(raw)
                else viewModel.setListsMessage(context.getString(R.string.settings_lists_import_failed))
            }.onFailure {
                viewModel.setListsMessage(context.getString(R.string.settings_lists_import_failed))
            }
        }
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.str_076), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MangaColors.Cyan) }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ProfileHeroSection(profile, avatarUri) { avatarLauncher.launch("image/*") }
            Spacer(Modifier.height(20.dp))

            Section(stringResource(R.string.more_profile), Icons.Filled.Person, MangaColors.Cyan, "profile", expandedSection, onToggle = { expandedSection = it }) {
                ProfileInfoSection(profile, formatJoinDate(context, profile?.updatedAt ?: 0L)) { showEditProfile = true }
            }
            Section(stringResource(R.string.settings_account), Icons.Filled.AccountCircle, MangaColors.PrimaryLight, "account", expandedSection, onToggle = { expandedSection = it }) {
                AccountInfoSection(userEmail, { showSignOutConfirm = true }, { showDeleteConfirm = true })
            }
            Section(stringResource(R.string.settings_security), Icons.Filled.Security, MangaColors.Green, "security", expandedSection, onToggle = { expandedSection = it }) {
                SecuritySection(
                    biometricEnabled = appSettings.biometricLockEnabled,
                    onToggleBiometric = viewModel::toggleBiometric,
                    loginCount = loginLogs.size,
                    deviceCount = devices.size,
                    sessionCount = sessions.size,
                    busy = securityBusy,
                    onOpenLoginLogs = { showLoginLogs = true },
                    onOpenDevices = { showDevices = true },
                    onOpenSessions = { showSessions = true },
                    onSignOutAll = { showSignOutAllConfirm = true }
                )
                securityError?.let { message ->
                    Text(message, color = MangaColors.Pink, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            Section(stringResource(R.string.settings_privacy), Icons.Filled.Visibility, MangaColors.Yellow, "privacy", expandedSection, onToggle = { expandedSection = it }) {
                PrivacySection(profile?.isPublic ?: true, profile?.showListsPublic ?: true, profile?.showActivityPublic ?: true, appSettings.showLibraryPublic, blockedUsers.size,
                    onTogglePublic = { p -> viewModel.updatePrivacy(profile?.showListsPublic ?: true, profile?.showActivityPublic ?: true, p) },
                    onToggleLists = { l -> viewModel.updatePrivacy(l, profile?.showActivityPublic ?: true, profile?.isPublic ?: true) },
                    onToggleActivity = { a -> viewModel.updatePrivacy(profile?.showListsPublic ?: true, a, profile?.isPublic ?: true) },
                    onToggleShowLibraryPublic = { enabled -> viewModel.toggleShowLibraryPublic(enabled) },
                    onShowBlockedUsers = { showBlockedUsers = true })
            }
            Section(stringResource(R.string.settings_library), Icons.Filled.LibraryBooks, MangaColors.Orange, "library", expandedSection, onToggle = { expandedSection = it }) {
                LibrarySection(favoriteCount, historyCount, readCount)
            }
            Section(stringResource(R.string.settings_notifications), Icons.Filled.Notifications, MangaColors.Pink, "notif", expandedSection, onToggle = { expandedSection = it }) {
                NotificationSection(
                    masterEnabled = appSettings.enableNotifications,
                    onToggleMaster = viewModel::toggleNotifications,
                    commentsEnabled = appSettings.notifyComments,
                    onToggleComments = viewModel::toggleNotifyComments,
                    likesEnabled = appSettings.notifyLikes,
                    onToggleLikes = viewModel::toggleNotifyLikes,
                    followersEnabled = appSettings.notifyFollowers,
                    onToggleFollowers = viewModel::toggleNotifyFollowers
                )
            }
            Section(stringResource(R.string.settings_sync), Icons.Filled.CloudSync, MangaColors.Cyan, "sync", expandedSection, onToggle = { expandedSection = it }) {
                SyncSection(
                    totalItems = favoriteCount + historyCount,
                    linkedProviderIds = linkedProviderIds,
                    providerLinkError = providerLinkError,
                    listsMessage = listsMessage,
                    onOpenCloudSync = onOpenCloudSync,
                    onExportLists = { exportLauncher.launch("mangaworld-lists.json") },
                    onImportLists = { importLauncher.launch(arrayOf("application/json")) },
                    onDismissListsMessage = viewModel::clearListsMessage,
                    onLinkGoogle = { googleLinkLauncher.launch(viewModel.googleSignInIntent()) },
                    onUnlinkProvider = viewModel::unlinkProvider,
                    onLinkFacebook = {
                        (context as? android.app.Activity)?.let { activity ->
                            com.facebook.login.LoginManager.getInstance().logInWithReadPermissions(
                                activity,
                                listOf("email", "public_profile")
                            )
                        }
                    }
                )
            }
            Section(stringResource(R.string.settings_sources), Icons.Filled.Tune, MangaColors.Green, "content", expandedSection, onToggle = { expandedSection = it }) {
                ContentSection(appSettings.enabledSources.size, appSettings.contentBlacklist.size, onOpenSources)
            }
            Section(stringResource(R.string.settings_social), Icons.Filled.People, MangaColors.Pink, "social", expandedSection, onToggle = { expandedSection = it }) {
                SocialInteractionSection(followingCount, followersCount, commentsCount, reviewsCount,
                    onShowFollowing = { showFollowingList = true },
                    onShowFollowers = { showFollowersList = true })
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_version_label, BuildConfig.VERSION_NAME), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }

    if (showEditProfile) EditProfileDialog(profile, { showEditProfile = false }) { u, d, b, loc, bd ->
        viewModel.updateProfile(u, b, d, loc, bd); showEditProfile = false
    }
    if (showDeleteConfirm) ConfirmDialog(stringResource(R.string.settings_delete_account), stringResource(R.string.settings_delete_account_confirm), stringResource(R.string.delete), { viewModel.deleteAccount(); showDeleteConfirm = false; onSignedOut() }, { showDeleteConfirm = false })
    if (showSignOutConfirm) ConfirmDialog(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_confirm), stringResource(R.string.logout), { viewModel.signOut(); showSignOutConfirm = false; onSignedOut() }, { showSignOutConfirm = false })
    if (showBlockedUsers) BlockedUsersDialog(blockedUsers, onDismiss = { showBlockedUsers = false }, onUnblock = { uid -> viewModel.unblockUser(uid) })
    if (showFollowingList) UserListDialog(stringResource(R.string.settings_following), following, onDismiss = { showFollowingList = false })
    if (showFollowersList) UserListDialog(stringResource(R.string.settings_followers), followers, onDismiss = { showFollowersList = false })
    if (showLoginLogs) LoginLogsDialog(
        logs = loginLogs,
        onDelete = viewModel::deleteLoginLog,
        onClear = viewModel::clearLoginLogs,
        onDismiss = { showLoginLogs = false }
    )
    if (showDevices) DevicesDialog(
        devices = devices,
        busy = securityBusy,
        onRemove = viewModel::removeDevice,
        onSignOutAll = { showDevices = false; showSignOutAllConfirm = true },
        onDismiss = { showDevices = false }
    )
    if (showSessions) SessionsDialog(
        sessions = sessions,
        busy = securityBusy,
        onRevoke = viewModel::revokeSession,
        onSignOutAll = { showSessions = false; showSignOutAllConfirm = true },
        onDismiss = { showSessions = false }
    )
    if (showSignOutAllConfirm) ConfirmDialog(
        stringResource(R.string.settings_security_sign_out_all),
        stringResource(R.string.settings_security_sign_out_all_confirm),
        stringResource(R.string.logout),
        { viewModel.signOutAllDevices(onSignedOut); showSignOutAllConfirm = false },
        { showSignOutAllConfirm = false }
    )
}

// ─── Profile Hero ───────────────────────────────────────────────────────────

@Composable
private fun ProfileHeroSection(profile: CommunityProfile?, avatarUri: Uri?, onAvatarClick: () -> Unit) {
    val roleText = profile?.role?.let { when(it) { "super-admin" -> stringResource(R.string.profile_role_admin); "moderator" -> stringResource(R.string.profile_role_moderator); else -> stringResource(R.string.profile_role_viewer) } } ?: stringResource(R.string.profile_role_viewer)
    val displayNameText = profile?.displayName?.takeIf { it.isNotBlank() } ?: profile?.username ?: stringResource(R.string.guest)
    Column(modifier = Modifier.fillMaxWidth().background(MangaColors.Surface).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(modifier = Modifier.size(96.dp).clip(CircleShape).background(MangaColors.PrimaryLight.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(88.dp).clip(CircleShape).background(MangaColors.Background), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(82.dp).clip(CircleShape).background(MangaColors.GlowPurple), contentAlignment = Alignment.Center) {
                        if (avatarUri != null) AsyncImage(model = avatarUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        else if (!profile?.avatarUrl.isNullOrBlank()) AsyncImage(model = profile.avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        else Text((displayNameText).take(1).uppercase(), color = MangaColors.PrimaryLight, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            IconButton(onClick = onAvatarClick, modifier = Modifier.size(28.dp).clip(CircleShape).background(MangaColors.Cyan)) {
                Icon(Icons.Filled.CameraAlt, stringResource(R.string.change_image), tint = MangaColors.Background, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayNameText, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            if (!profile?.badgeLabel.isNullOrBlank()) { Spacer(Modifier.width(8.dp)); Text(profile.badgeLabel, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MangaColors.GlowCyan).padding(horizontal = 10.dp, vertical = 4.dp)) }
        }
        if (profile?.username?.isNotBlank() == true && displayNameText != profile.username) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.profile_username_handle, profile.username), color = MangaColors.Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (!profile?.bio.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); Text(profile.bio, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 3) }
        Spacer(Modifier.height(8.dp))
        Text(roleText, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

// ─── Section Wrapper ────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, icon: ImageVector, tint: Color, key: String, expanded: String?, onToggle: (String?) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val isExpanded = expanded == key
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MangaColors.SurfaceContainer)) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onToggle(if (isExpanded) null else key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MangaColors.Muted, modifier = Modifier.size(20.dp))
        }
        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

// ─── Section Content ────────────────────────────────────────────────────────

@Composable private fun ProfileInfoSection(profile: CommunityProfile?, joinDateText: String, onEdit: () -> Unit) {
    val roleText = profile?.role?.let { when(it) { "super-admin" -> stringResource(R.string.profile_role_admin); "moderator" -> stringResource(R.string.profile_role_moderator); else -> stringResource(R.string.profile_role_viewer) } } ?: stringResource(R.string.profile_role_viewer)
    val displayNameText = profile?.displayName?.takeIf { it.isNotBlank() } ?: profile?.username ?: stringResource(R.string.guest)
    val birthdayText = profile?.birthday?.takeIf { it > 0L }?.let { formatJoinDate(androidx.compose.ui.platform.LocalContext.current, it) } ?: stringResource(R.string.profile_birthday_not_set)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Badge, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_display_name), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(displayNameText, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.AlternateEmail, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_username), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.username ?: stringResource(R.string.unspecified), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Info, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_bio), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.bio?.ifBlank { stringResource(R.string.no_bio) } ?: stringResource(R.string.no_bio), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.LocationOn, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_location), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.location?.ifBlank { stringResource(R.string.profile_birthday_not_set) } ?: stringResource(R.string.profile_birthday_not_set), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cake, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_birthday), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(birthdayText, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CalendarToday, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_join_date), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(joinDateText, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.EmojiEvents, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_role), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(roleText, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (!profile?.badgeLabel.isNullOrBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Star, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.profile_badge), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile.badgeLabel, color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MangaColors.Cyan)) { Text(stringResource(R.string.profile_edit), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable private fun AccountInfoSection(userEmail: String?, onSignOut: () -> Unit, onDeleteAccount: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Email, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_email), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(userEmail ?: stringResource(R.string.settings_unavailable), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onDeleteAccount).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_delete_account), color = MangaColors.Error, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSignOut).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Logout, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_sign_out), color = MangaColors.Error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun SecuritySection(
    biometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    loginCount: Int,
    deviceCount: Int,
    sessionCount: Int,
    busy: Boolean,
    onOpenLoginLogs: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSessions: () -> Unit,
    onSignOutAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Fingerprint, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_biometric), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = biometricEnabled, onCheckedChange = onToggleBiometric, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenLoginLogs).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_login_history), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, loginCount), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenDevices).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Devices, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_devices), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, deviceCount), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSessions).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Security, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.manage_sessions), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, sessionCount), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onSignOutAll).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Logout, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.settings_security_sign_out_all), color = MangaColors.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(color = MangaColors.Error, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable private fun PrivacySection(isPublic: Boolean, showLists: Boolean, showActivity: Boolean, showLibraryPublic: Boolean, blockedCount: Int, onTogglePublic: (Boolean) -> Unit, onToggleLists: (Boolean) -> Unit, onToggleActivity: (Boolean) -> Unit, onToggleShowLibraryPublic: (Boolean) -> Unit, onShowBlockedUsers: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Public, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.public_account), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = isPublic, onCheckedChange = onTogglePublic, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.List, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_show_lists_public), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = showLists, onCheckedChange = onToggleLists, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_show_activity_public), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = showActivity, onCheckedChange = onToggleActivity, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.LibraryBooks, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_show_library_public), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = showLibraryPublic, onCheckedChange = onToggleShowLibraryPublic, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onShowBlockedUsers).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Block, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_block_users), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_023, blockedCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun LibrarySection(favCount: Int, histCount: Int, readCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Favorite, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.favorite_manga), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_034, favCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.AutoStories, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.library_reading), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_034, favCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.reading_history), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_034, histCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MenuBook, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.read_chapters), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_017, readCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun NotificationSection(
    masterEnabled: Boolean,
    onToggleMaster: (Boolean) -> Unit,
    commentsEnabled: Boolean,
    onToggleComments: (Boolean) -> Unit,
    likesEnabled: Boolean,
    onToggleLikes: (Boolean) -> Unit,
    followersEnabled: Boolean,
    onToggleFollowers: (Boolean) -> Unit
) {
    @Composable
    fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onToggle: (Boolean) -> Unit, rowEnabled: Boolean = true) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(label, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onToggle, enabled = rowEnabled, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications_new_chapters), masterEnabled, onToggleMaster)
        ToggleRow(Icons.Filled.ChatBubble, stringResource(R.string.settings_notifications_comments), commentsEnabled && masterEnabled, onToggleComments, masterEnabled)
        ToggleRow(Icons.Filled.FavoriteBorder, stringResource(R.string.settings_notifications_likes), likesEnabled && masterEnabled, onToggleLikes, masterEnabled)
        ToggleRow(Icons.Filled.PersonAdd, stringResource(R.string.settings_notifications_followers), followersEnabled && masterEnabled, onToggleFollowers, masterEnabled)
    }
}

@Composable private fun SyncSection(
    totalItems: Int,
    linkedProviderIds: Set<String>,
    providerLinkError: String?,
    listsMessage: String?,
    onOpenCloudSync: () -> Unit,
    onExportLists: () -> Unit,
    onImportLists: () -> Unit,
    onDismissListsMessage: () -> Unit,
    onLinkGoogle: () -> Unit,
    onLinkFacebook: () -> Unit,
    onUnlinkProvider: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCloudSync).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cloud, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_cloud_sync), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.open), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onExportLists).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Upload, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_export_lists), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_034, totalItems), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onImportLists).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Download, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_import_lists), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
        listsMessage?.let { message ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MangaColors.Green, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(message, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissListsMessage) { Text(stringResource(R.string.close), color = MangaColors.Muted) }
            }
        }
        ProviderLinkRow(
            label = "Google",
            providerId = "google.com",
            linked = "google.com" in linkedProviderIds,
            canUnlink = linkedProviderIds.size > 1,
            onLink = onLinkGoogle,
            onUnlink = onUnlinkProvider
        )
        ProviderLinkRow(
            label = "Facebook",
            providerId = "facebook.com",
            linked = "facebook.com" in linkedProviderIds,
            canUnlink = linkedProviderIds.size > 1,
            onLink = onLinkFacebook,
            onUnlink = onUnlinkProvider
        )
        if ("password" in linkedProviderIds) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Email, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.settings_email), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.settings_provider_linked), color = MangaColors.Green, style = MaterialTheme.typography.bodySmall)
            }
        }
        providerLinkError?.let { message ->
            Text(message, color = MangaColors.Pink, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ProviderLinkRow(
    label: String,
    providerId: String,
    linked: Boolean,
    canUnlink: Boolean,
    onLink: () -> Unit,
    onUnlink: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !linked, onClick = onLink)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            Icon(Icons.Filled.Link, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (linked && canUnlink) {
                TextButton(onClick = { onUnlink(providerId) }) { Text(stringResource(R.string.remove), color = MangaColors.Pink) }
            } else {
                Text(if (linked) stringResource(R.string.settings_provider_linked) else stringResource(R.string.link), color = if (linked) MangaColors.Green else MangaColors.Cyan, style = MaterialTheme.typography.bodySmall)
            }
    }
}

@Composable private fun ContentSection(srcCount: Int, blacklistCount: Int, onOpenSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSources).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Tune, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.favorite_translation_sources), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.fmt_019, srcCount), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Block, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text(stringResource(R.string.content_filter_settings), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium); Text(stringResource(R.string.fmt_001, blacklistCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable private fun SocialInteractionSection(followingCount: Int, followersCount: Int, commentsCount: Int, reviewsCount: Int, onShowFollowing: () -> Unit, onShowFollowers: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onShowFollowing).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.PersonAdd, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_following), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, followingCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onShowFollowers).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.People, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_followers), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, followersCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Comment, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_comments_count), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, commentsCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.RateReview, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.settings_reviews_count), color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(stringResource(R.string.settings_count_format, reviewsCount), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

// ─── Dialogs ────────────────────────────────────────────────────────────────

@Composable private fun EditProfileDialog(profile: CommunityProfile?, onDismiss: () -> Unit, onSave: (String, String, String, String, Long?) -> Unit) {
    var username by remember { mutableStateOf(profile?.username ?: "") }
    var displayName by remember { mutableStateOf(profile?.displayName ?: "") }
    var bio by remember { mutableStateOf(profile?.bio ?: "") }
    var location by remember { mutableStateOf(profile?.location ?: "") }
    var birthday by remember { mutableStateOf(profile?.birthday?.takeIf { it > 0L }) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val normalizedUsername = username.trim().lowercase()
    val usernameError = when {
        normalizedUsername.isEmpty() -> stringResource(R.string.auth_error_username_required)
        normalizedUsername.length < 3 -> stringResource(R.string.auth_error_username_short)
        normalizedUsername.length > 20 -> stringResource(R.string.auth_error_username_long)
        !normalizedUsername.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$")) -> stringResource(R.string.str_012)
        else -> null
    }

    fun openBirthdayPicker() {
        val cal = java.util.Calendar.getInstance()
        birthday?.takeIf { it > 0L }?.let { cal.timeInMillis = it }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                cal.set(java.util.Calendar.YEAR, year)
                cal.set(java.util.Calendar.MONTH, month)
                cal.set(java.util.Calendar.DAY_OF_MONTH, day)
                birthday = cal.timeInMillis
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(stringResource(R.string.profile_edit), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.profile_display_name)) },
                placeholder = { Text(stringResource(R.string.profile_username_display_name_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.profile_username)) },
                placeholder = { Text(stringResource(R.string.auth_username_rules)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
            )
            if (usernameError != null) {
                Text(usernameError, color = MangaColors.Yellow, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = bio, onValueChange = { bio = it },
                label = { Text(stringResource(R.string.profile_bio)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
            )
            OutlinedTextField(
                value = location, onValueChange = { location = it.take(64) },
                label = { Text(stringResource(R.string.profile_location)) },
                placeholder = { Text(stringResource(R.string.profile_location_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = ::openBirthdayPicker,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MangaColors.OnSurface)
                ) {
                    Text(
                        birthday?.takeIf { it > 0L }?.let { formatJoinDate(context, it) }
                            ?: stringResource(R.string.profile_birthday_not_set),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (birthday != null) {
                    TextButton(onClick = { birthday = null }) { Text(stringResource(R.string.clear), color = MangaColors.Muted) }
                }
            }
            Text(stringResource(R.string.profile_birthday), color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }},
        confirmButton = {
            Button(
                onClick = { onSave(normalizedUsername, displayName.trim(), bio.trim(), location.trim(), birthday) },
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan),
                enabled = usernameError == null && normalizedUsername.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = MangaColors.Muted) } }
    )
}

@Composable private fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = MangaColors.OnSurfaceVariant) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)) { Text(confirmText, color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = MangaColors.Muted) } }
    )
}

@Composable private fun BlockedUsersDialog(blockedUsers: Set<String>, onDismiss: () -> Unit, onUnblock: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(stringResource(R.string.settings_blocked_users_title), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            if (blockedUsers.isEmpty()) {
                Text(stringResource(R.string.settings_blocked_empty), color = MangaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    blockedUsers.forEach { uid ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(uid.take(16) + "...", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onUnblock(uid) }) { Text(stringResource(R.string.settings_unblock), color = MangaColors.Cyan) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = MangaColors.Muted) } }
    )
}

@Composable private fun UserListDialog(title: String, users: List<UserFollow>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            if (users.isEmpty()) {
                Text(stringResource(R.string.dialog_no_users), color = MangaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    users.forEach { user ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MangaColors.PrimaryLight.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                                if (user.avatarUrl != null) AsyncImage(model = user.avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                                else Text(user.username.take(1).uppercase(), color = MangaColors.PrimaryLight, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(user.username, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = MangaColors.Muted) } }
    )
}

// ─── Security centre dialogs ──────────────────────────────────────────────────

@Composable private fun LoginLogsDialog(
    logs: List<com.exapps.mangaworld.domain.model.LoginLogEntry>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(stringResource(R.string.settings_login_history), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            if (logs.isEmpty()) {
                Text(stringResource(R.string.settings_login_history_empty), color = MangaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settings_security_clear_logs), color = MangaColors.Pink) }
                    logs.forEach { log ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Smartphone, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(log.deviceLabel.ifBlank { stringResource(R.string.settings_unavailable) }, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${formatJoinDate(context, log.createdAt)}${if (log.provider.isNotBlank()) " · ${log.provider}" else ""}${if (log.appVersion.isNotBlank()) " · v${log.appVersion}" else ""}",
                                    color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = { onDelete(log.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.settings_security_remove), tint = MangaColors.Muted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = MangaColors.Muted) } }
    )
}

@Composable private fun DevicesDialog(
    devices: List<com.exapps.mangaworld.domain.model.DeviceEntry>,
    busy: Boolean,
    onRemove: (String) -> Unit,
    onSignOutAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(stringResource(R.string.settings_devices), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.settings_security_empty), color = MangaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = onSignOutAll, enabled = !busy, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settings_security_sign_out_all), color = MangaColors.Error) }
                    devices.forEach { device ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Smartphone, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        device.platform.ifBlank { stringResource(R.string.settings_unavailable) },
                                        color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (device.isCurrent) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.settings_security_current), color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(
                                    "••${device.tokenSuffix} · ${formatJoinDate(context, device.updatedAt)}",
                                    color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (!device.isCurrent) {
                                IconButton(onClick = { onRemove(device.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.settings_security_remove), tint = MangaColors.Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = MangaColors.Muted) } }
    )
}

@Composable private fun SessionsDialog(
    sessions: List<com.exapps.mangaworld.domain.model.SessionEntry>,
    busy: Boolean,
    onRevoke: (String) -> Unit,
    onSignOutAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(stringResource(R.string.manage_sessions), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.settings_security_empty), color = MangaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = onSignOutAll, enabled = !busy, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settings_security_sign_out_all), color = MangaColors.Error) }
                    sessions.forEach { session ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Security, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        session.deviceLabel.ifBlank { stringResource(R.string.settings_unavailable) },
                                        color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (session.isCurrent) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.settings_security_current), color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(
                                    formatJoinDate(context, session.lastSeenAt),
                                    color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (!session.revoked && !session.isCurrent) {
                                TextButton(onClick = { onRevoke(session.id) }, enabled = !busy) { Text(stringResource(R.string.settings_security_revoke), color = MangaColors.Pink) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = MangaColors.Muted) } }
    )
}
