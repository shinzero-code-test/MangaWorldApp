package com.exapps.mangaworld.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.firebase.CloudinaryUploader
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ──────────────────────────────────────────────────────────────

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager,
    private val readingStatsStore: ReadingStatsStore,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readChapterDao: ReadChapterDao,
    private val cloudinaryUploader: CloudinaryUploader
) : ViewModel() {
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val profile = kotlinx.coroutines.flow.flow { emit(communityRepository.getCurrentProfile()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appSettings = settingsRepository.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val totalReadingTimeMs = readingStatsStore.totalReadingTimeMs.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val totalMangaRead = readingStatsStore.totalMangaRead.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val currentStreak = readingStatsStore.currentStreak.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _favoriteCount = MutableStateFlow(0)
    val favoriteCount: StateFlow<Int> = _favoriteCount.asStateFlow()
    private val _historyCount = MutableStateFlow(0)
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()
    private val _readCount = MutableStateFlow(0)
    val readCount: StateFlow<Int> = _readCount.asStateFlow()

    var avatarUri by mutableStateOf<Uri?>(null); private set

    init {
        viewModelScope.launch {
            profile.first { it != null }
            _isLoading.value = false
            _favoriteCount.value = favoriteDao.getFavoritesList().size
            _historyCount.value = historyDao.getAll().size
            _readCount.value = readChapterDao.getTotalReadCount()
        }
    }

    fun updateAvatarUri(uri: Uri) { avatarUri = uri }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            val current = communityRepository.getCurrentProfile()
            current?.avatarUrl?.let { cloudinaryUploader.extractPublicId(it)?.let { id -> cloudinaryUploader.deleteImage(id) } }
            val result = cloudinaryUploader.uploadImage(uri, folder = "avatars")
            if (result != null) {
                communityRepository.upsertProfile(current?.username ?: "", current?.bio ?: "", current?.isPublic ?: true, result.url, current?.bannerUrl)
                avatarUri = null
            }
        }
    }

    fun updateProfile(username: String, bio: String) {
        viewModelScope.launch {
            val c = communityRepository.getCurrentProfile()
            communityRepository.upsertProfile(username.ifBlank { c?.username ?: "" }, bio, c?.isPublic ?: true, c?.avatarUrl, c?.bannerUrl)
        }
    }

    fun updatePrivacy(showLists: Boolean, showActivity: Boolean, isPublic: Boolean) {
        viewModelScope.launch {
            val c = communityRepository.getCurrentProfile()
            communityRepository.upsertProfile(c?.username ?: "", c?.bio ?: "", isPublic, c?.avatarUrl, c?.bannerUrl)
            communityRepository.updateProfilePrivacy(showLists, showActivity)
        }
    }

    fun toggleNotifications(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) } }
    fun toggleBiometric(enabled: Boolean) { viewModelScope.launch { settingsRepository.setBiometricLock(enabled) } }
    fun signOut() { viewModelScope.launch { sessionManager.signOut() } }
}

// ─── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    onBack: () -> Unit,
    onOpenReadingStats: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenCollections: () -> Unit,
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val totalReadingTimeMs by viewModel.totalReadingTimeMs.collectAsStateWithLifecycle()
    val totalMangaRead by viewModel.totalMangaRead.collectAsStateWithLifecycle()
    val currentStreak by viewModel.currentStreak.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val readCount by viewModel.readCount.collectAsStateWithLifecycle()
    val avatarUri = viewModel.avatarUri

    var expandedSection by remember { mutableStateOf<String?>(null) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.uploadAvatar(it) }
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("إعدادات الحساب", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface) } },
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

            Section("الملف الشخصي", Icons.Filled.Person, MangaColors.Cyan, "profile", expandedSection, onToggle = { expandedSection = it }) {
                ProfileInfoSection(profile) { showEditProfile = true }
            }
            Section("معلومات الحساب", Icons.Filled.AccountCircle, MangaColors.PrimaryLight, "account", expandedSection, onToggle = { expandedSection = it }) {
                AccountInfoSection({ showSignOutConfirm = true }, { showDeleteConfirm = true })
            }
            Section("الأمان", Icons.Filled.Security, MangaColors.Green, "security", expandedSection, onToggle = { expandedSection = it }) {
                SecuritySection(appSettings.biometricLockEnabled, viewModel::toggleBiometric)
            }
            Section("الخصوصية", Icons.Filled.Visibility, MangaColors.Yellow, "privacy", expandedSection, onToggle = { expandedSection = it }) {
                PrivacySection(profile?.isPublic ?: true, profile?.showListsPublic ?: true, profile?.showActivityPublic ?: true,
                    onTogglePublic = { p -> viewModel.updatePrivacy(profile?.showListsPublic ?: true, profile?.showActivityPublic ?: true, p) },
                    onToggleLists = { l -> viewModel.updatePrivacy(l, profile?.showActivityPublic ?: true, profile?.isPublic ?: true) },
                    onToggleActivity = { a -> viewModel.updatePrivacy(profile?.showListsPublic ?: true, a, profile?.isPublic ?: true) })
            }
            Section("المكتبة الشخصية", Icons.Filled.LibraryBooks, MangaColors.Orange, "library", expandedSection, onToggle = { expandedSection = it }) {
                LibrarySection(favoriteCount, historyCount, readCount, onOpenCollections)
            }
            Section("الإشعارات", Icons.Filled.Notifications, MangaColors.Pink, "notif", expandedSection, onToggle = { expandedSection = it }) {
                NotificationSection(appSettings.enableNotifications, viewModel::toggleNotifications)
            }
            Section("الإنجازات والإحصائيات", Icons.Filled.BarChart, MangaColors.Cyan, "stats", expandedSection, onToggle = { expandedSection = it }) {
                StatsSection(totalReadingTimeMs, totalMangaRead, currentStreak, onOpenReadingStats)
            }
            Section("المزامنة والنسخ الاحتياطي", Icons.Filled.CloudSync, MangaColors.Cyan, "sync", expandedSection, onToggle = { expandedSection = it }) {
                SyncSection(onOpenCloudSync)
            }
            Section("المصادر والمحتوى", Icons.Filled.Tune, MangaColors.Green, "content", expandedSection, onToggle = { expandedSection = it }) {
                ContentSection(appSettings.enabledSources.size, appSettings.contentBlacklist.size, onOpenSources)
            }
            Spacer(Modifier.height(16.dp))
            Text("MangaWorld v5.1.0", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }

    if (showEditProfile) EditProfileDialog(profile, { showEditProfile = false }) { u, b -> viewModel.updateProfile(u, b); showEditProfile = false }
    if (showDeleteConfirm) ConfirmDialog("حذف الحساب", "هل أنت متأكد من حذف حسابك؟ هذا الإجراء لا يمكن التراجع عنه.", "حذف", { showDeleteConfirm = false }, { showDeleteConfirm = false })
    if (showSignOutConfirm) ConfirmDialog("تسجيل الخروج", "هل تريد تسجيل الخروج من حسابك؟", "خروج", { viewModel.signOut(); showSignOutConfirm = false }, { showSignOutConfirm = false })
}

// ─── Profile Hero ───────────────────────────────────────────────────────────

@Composable
private fun ProfileHeroSection(profile: CommunityProfile?, avatarUri: Uri?, onAvatarClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MangaColors.Surface).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(modifier = Modifier.size(96.dp).clip(CircleShape).background(MangaColors.PrimaryLight.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(88.dp).clip(CircleShape).background(MangaColors.Background), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(82.dp).clip(CircleShape).background(MangaColors.GlowPurple), contentAlignment = Alignment.Center) {
                        if (avatarUri != null) AsyncImage(model = avatarUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        else if (!profile?.avatarUrl.isNullOrBlank()) AsyncImage(model = profile.avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        else Text((profile?.username ?: "G").take(1).uppercase(), color = MangaColors.PrimaryLight, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            IconButton(onClick = onAvatarClick, modifier = Modifier.size(28.dp).clip(CircleShape).background(MangaColors.Cyan)) {
                Icon(Icons.Filled.CameraAlt, "تغيير الصورة", tint = MangaColors.Background, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profile?.username ?: "ضيف", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            if (!profile?.badgeLabel.isNullOrBlank()) { Spacer(Modifier.width(8.dp)); Text(profile.badgeLabel, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MangaColors.GlowCyan).padding(horizontal = 10.dp, vertical = 4.dp)) }
        }
        if (!profile?.bio.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); Text(profile.bio, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 3) }
        Spacer(Modifier.height(8.dp))
        Text(profile?.role?.let { when(it) { "admin" -> "مدير"; "moderator" -> "مشرف"; else -> "قارئ" } } ?: "قارئ", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
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

@Composable private fun ProfileInfoSection(profile: CommunityProfile?, onEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Badge, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الاسم المعروض", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.username ?: "ضيف", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Info, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("النبذة الشخصية", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.bio?.ifBlank { "لا توجد نبذة" } ?: "لا توجد نبذة", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CalendarToday, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("تاريخ الانضمام", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("حساب عضو", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.EmojiEvents, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الرتبة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile?.role?.let { when(it) { "admin" -> "مدير"; "moderator" -> "مشرف"; else -> "قارئ" } } ?: "قارئ", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (!profile?.badgeLabel.isNullOrBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Star, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الشارة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(profile.badgeLabel, color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MangaColors.Cyan)) { Text("تعديل الملف الشخصي", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable private fun AccountInfoSection(onSignOut: () -> Unit, onDeleteAccount: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Email, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("البريد الإلكتروني", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("محفوظ في Firebase", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Phone, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("رقم الهاتف", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("غير مضاف", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onDeleteAccount).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("حذف الحساب", color = MangaColors.Error, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSignOut).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Logout, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("تسجيل الخروج", color = MangaColors.Error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun SecuritySection(biometricEnabled: Boolean, onToggleBiometric: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Fingerprint, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("القفل البيومتري", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = biometricEnabled, onCheckedChange = onToggleBiometric, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("سجل تسجيل الدخول", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("قريباً", color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Devices, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الأجهزة المتصلة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("قريباً", color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun PrivacySection(isPublic: Boolean, showLists: Boolean, showActivity: Boolean, onTogglePublic: (Boolean) -> Unit, onToggleLists: (Boolean) -> Unit, onToggleActivity: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Public, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الحساب العام", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = isPublic, onCheckedChange = onTogglePublic, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.List, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إظهار قائمة القراءة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = showLists, onCheckedChange = onToggleLists, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إظهار النشاط الأخير", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = showActivity, onCheckedChange = onToggleActivity, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Block, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("حظر المستخدمين", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("قريباً", color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun LibrarySection(favCount: Int, histCount: Int, readCount: Int, onOpenCollections: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Favorite, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("المانجا المفضلة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$favCount مانجا", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.History, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("سجل القراءة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$histCount مانجا", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MenuBook, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الفصول المقروءة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$readCount فصل", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCollections).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CollectionsBookmark, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("القوائم المخصصة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("فتح", color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun NotificationSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Notifications, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إشعارات الفصول الجديدة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = MangaColors.Cyan, checkedTrackColor = MangaColors.CyanDim, uncheckedThumbColor = MangaColors.Muted, uncheckedTrackColor = MangaColors.SurfaceHigh)) }
        val status = if (enabled) "مفعل" else "معطل"
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.ChatBubble, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إشعارات التعليقات", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(status, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.FavoriteBorder, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إشعارات الإعجابات", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(status, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.PersonAdd, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("إشعارات المتابعين", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(status, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun StatsSection(timeMs: Long, chapters: Int, streak: Int, onOpenStats: () -> Unit) {
    val h = (timeMs / 3_600_000).toInt(); val m = ((timeMs % 3_600_000) / 60_000).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MenuBook, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الفصول المقروءة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$chapters فصل", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.AccessTime, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("وقت القراءة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(if (h > 0) "${h}س ${m}د" else "${m}د", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Whatshot, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الأيام المتتالية", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$streak يوم", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenStats).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.EmojiEvents, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الإنجازات", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("فتح", color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun SyncSection(onOpenCloudSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCloudSync).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cloud, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("مزامنة الحساب مع السحابة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("فتح", color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.ImportExport, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("استيراد/تصدير القوائم", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("عبر السحابة", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Link, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("ربط الحساب بخدمات خارجية", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("Google ✓", color = MangaColors.Green, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun ContentSection(srcCount: Int, blacklistCount: Int, onOpenSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSources).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Tune, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("مصادر الترجمة المفضلة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$srcCount مصدر", color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Block, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)); Text("الكلمات المحظورة", color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text("$blacklistCount كلمة", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

// ─── Dialogs ────────────────────────────────────────────────────────────────

@Composable private fun EditProfileDialog(profile: CommunityProfile?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var username by remember { mutableStateOf(profile?.username ?: "") }; var bio by remember { mutableStateOf(profile?.bio ?: "") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text("تعديل الملف الشخصي", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))
            OutlinedTextField(value = bio, onValueChange = { bio = it }, label = { Text("النبذة الشخصية") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 4, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))
        }},
        confirmButton = { Button(onClick = { onSave(username, bio) }, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = MangaColors.Muted) } }
    )
}

@Composable private fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaColors.Background,
        title = { Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = MangaColors.OnSurfaceVariant) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)) { Text(confirmText, color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = MangaColors.Muted) } }
    )
}
