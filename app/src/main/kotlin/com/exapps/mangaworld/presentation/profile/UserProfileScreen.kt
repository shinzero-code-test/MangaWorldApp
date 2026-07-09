package com.exapps.mangaworld.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =====================================================================================
// ViewModel — business logic is unchanged from the original implementation.
// =====================================================================================

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val readingStatsStore: ReadingStatsStore,
    private val cloudinaryUploader: com.exapps.mangaworld.core.firebase.CloudinaryUploader
) : ViewModel() {
    val profile = kotlinx.coroutines.flow.flow { emit(communityRepository.getCurrentProfile()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val notifications = communityRepository.observeNotifications(20)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lists = communityRepository.observeUserLists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalReadingTimeMs = readingStatsStore.totalReadingTimeMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val totalMangaRead = readingStatsStore.totalMangaRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val currentStreak = readingStatsStore.currentStreak
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    var avatarUri by mutableStateOf<Uri?>(null)
        private set

    fun updateAvatarUri(uri: Uri) {
        avatarUri = uri
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            val url = cloudinaryUploader.uploadImage(uri, folder = "avatars")
            if (url != null) {
                val current = communityRepository.getCurrentProfile()
                communityRepository.upsertProfile(
                    username = current?.username ?: "",
                    bio = current?.bio ?: "",
                    isPublic = current?.isPublic ?: true,
                    avatarUrl = url
                )
                avatarUri = null
            }
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch { runCatching { communityRepository.markNotificationRead(id) } }
    }

    fun updatePrivacy(showListsPublic: Boolean, showActivityPublic: Boolean) {
        viewModelScope.launch { runCatching { communityRepository.updateProfilePrivacy(showListsPublic, showActivityPublic) } }
    }
}

// =====================================================================================
// Design constants — local to this screen (spacing / sizing tokens only, no new colors)
// =====================================================================================

private val HeroCoverHeight = 208.dp
private val HeroOverlap = 56.dp
private val AvatarSize = 96.dp

// =====================================================================================
// Screen
// =====================================================================================

@Composable
fun UserProfileScreen(
    onOpenCloudSync: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCommunityChat: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenModeration: () -> Unit,
    onOpenReadingStats: () -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val totalReadingTimeMs by viewModel.totalReadingTimeMs.collectAsStateWithLifecycle()
    val totalMangaRead by viewModel.totalMangaRead.collectAsStateWithLifecycle()
    val currentStreak by viewModel.currentStreak.collectAsStateWithLifecycle()
    val avatarUri = viewModel.avatarUri

    var showListsPublic by remember(profile?.showListsPublic) { mutableStateOf(profile?.showListsPublic ?: true) }
    var showActivityPublic by remember(profile?.showActivityPublic) { mutableStateOf(profile?.showActivityPublic ?: true) }

    val unreadNotifications = notifications.count { !it.read }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.uploadAvatar(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        ProfileHeader(
            profile = profile,
            avatarUri = avatarUri,
            onAvatarClick = { avatarLauncher.launch("image/*") }
        )

        StatsRow(
            totalReadingTimeMs = totalReadingTimeMs,
            chaptersRead = totalMangaRead,
            streak = currentStreak
        )

        QuickActionsGrid(
            unreadNotifications = unreadNotifications,
            onOpenCloudSync = onOpenCloudSync,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenCommunityChat = onOpenCommunityChat,
            onOpenNotifications = onOpenNotifications,
            onOpenReadingStats = onOpenReadingStats
        )

        CustomListsSection(
            lists = lists,
            onOpenLists = onOpenLists
        )

        PrivacySettingsSection(
            showListsPublic = showListsPublic,
            showActivityPublic = showActivityPublic,
            onToggleLists = {
                showListsPublic = it
                viewModel.updatePrivacy(showListsPublic, showActivityPublic)
            },
            onToggleActivity = {
                showActivityPublic = it
                viewModel.updatePrivacy(showListsPublic, showActivityPublic)
            }
        )

        NotificationsSection(
            notifications = notifications,
            unreadCount = unreadNotifications,
            onNotificationClick = { viewModel.markRead(it.id) }
        )

        if (profile?.role in setOf("moderator", "admin")) {
            ModerationEntryCard(onClick = onOpenModeration)
        }
    }
}

// =====================================================================================
// Hero header — cover gradient + overlapping avatar with camera edit badge
// =====================================================================================

@Composable
private fun ProfileHeader(profile: CommunityProfile?, avatarUri: Uri?, onAvatarClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HeroCoverHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MangaColors.PrimaryDim.copy(alpha = 0.45f), MangaColors.Background)
                    )
                )
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(MangaColors.Cyan.copy(alpha = 0.2f), Color.Transparent),
                            center = Offset(size.width * 0.2f, size.height * 0.25f),
                            radius = size.width * 0.7f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(MangaColors.PrimaryLight.copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 1.0f),
                            radius = size.width * 0.65f
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HeroCoverHeight - HeroOverlap)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(MangaColors.PrimaryLight.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(AvatarSize - 6.dp)
                            .clip(CircleShape)
                            .background(MangaColors.Background),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(AvatarSize - 12.dp)
                                .clip(CircleShape)
                                .background(MangaColors.GlowPurple),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUri != null) {
                                AsyncImage(
                                    model = avatarUri,
                                    contentDescription = "صورة الملف الشخصي",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (!profile?.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = profile.avatarUrl,
                                    contentDescription = "صورة الملف الشخصي",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = (profile?.username ?: "G").take(1).uppercase(),
                                    color = MangaColors.PrimaryLight,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = onAvatarClick,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MangaColors.Cyan)
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "تغيير الصورة",
                        tint = MangaColors.Background,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile?.username ?: "ضيف",
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                if (!profile?.badgeLabel.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = profile.badgeLabel,
                        color = MangaColors.Cyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MangaColors.GlowCyan)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            if (!profile?.bio.isNullOrBlank()) {
                Text(
                    text = profile.bio,
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// =====================================================================================
// Reading stats
// =====================================================================================

@Composable
private fun StatsRow(totalReadingTimeMs: Long, chaptersRead: Int, streak: Int) {
    val hours = (totalReadingTimeMs / 3_600_000).toInt()
    val mins = ((totalReadingTimeMs % 3_600_000) / 60_000).toInt()
    val readingTimeText = if (hours > 0) "${hours}س ${mins}د" else "${mins}د"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(modifier = Modifier.weight(1f), icon = Icons.Filled.AccessTime, tint = MangaColors.Cyan, value = readingTimeText, label = "وقت القراءة")
        StatCard(modifier = Modifier.weight(1f), icon = Icons.Filled.MenuBook, tint = MangaColors.Cyan, value = chaptersRead.toString(), label = "فصول مقروءة")
        StatCard(modifier = Modifier.weight(1f), icon = Icons.Filled.Whatshot, tint = MangaColors.Orange, value = "$streak", label = "أيام متتالية")
    }
}

@Composable
private fun StatCard(modifier: Modifier, icon: ImageVector, tint: Color, value: String, label: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(text = value, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(text = label, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

// =====================================================================================
// Quick actions
// =====================================================================================

private data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val badgeCount: Int,
    val onClick: () -> Unit
)

@Composable
private fun QuickActionsGrid(
    unreadNotifications: Int,
    onOpenCloudSync: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCommunityChat: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenReadingStats: () -> Unit
) {
    val actions = listOf(
        QuickAction(Icons.Filled.CloudSync, "السحابة", MangaColors.Cyan, 0, onOpenCloudSync),
        QuickAction(Icons.Filled.Tune, "التشخيص", MangaColors.Pink, 0, onOpenDiagnostics),
        QuickAction(Icons.Filled.Chat, "الدردشة", MangaColors.Green, 0, onOpenCommunityChat),
        QuickAction(Icons.Filled.Notifications, "إشعارات", MangaColors.Yellow, unreadNotifications, onOpenNotifications),
        QuickAction(Icons.Filled.Speed, "إحصائيات", MangaColors.Orange, 0, onOpenReadingStats)
    )
    val rows = actions.chunked(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(16.dp)
    ) {
        Text("إجراءات سريعة", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(16.dp))
        rows.forEachIndexed { rowIndex, rowActions ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowActions.forEach { action ->
                    QuickActionItem(modifier = Modifier.weight(1f), action = action)
                }
                repeat(3 - rowActions.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (rowIndex < rows.lastIndex) {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun QuickActionItem(modifier: Modifier = Modifier, action: QuickAction) {
    Column(
        modifier = modifier.clickable(onClick = action.onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(action.tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(action.icon, contentDescription = action.label, tint = action.tint, modifier = Modifier.size(24.dp))
            }
            if (action.badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MangaColors.Pink),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (action.badgeCount > 9) "9+" else action.badgeCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(action.label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// =====================================================================================
// My Lists
// =====================================================================================

@Composable
private fun CustomListsSection(lists: List<CustomUserList>, onOpenLists: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("قوائمي", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.clickable(onClick = onOpenLists),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("عرض الكل", color = MangaColors.Cyan, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MangaColors.Cyan,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (lists.isEmpty()) {
            Text("لا توجد قوائم بعد", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    ListCardItem(list = list, onClick = onOpenLists)
                }
            }
        }
    }
}

@Composable
private fun ListCardItem(list: CustomUserList, onClick: () -> Unit) {
    val cardColor = remember(list.id) {
        val colors = listOf(MangaColors.PrimaryDim, MangaColors.CyanDim, MangaColors.Pink.copy(alpha = 0.5f), MangaColors.Orange.copy(alpha = 0.5f), MangaColors.Green.copy(alpha = 0.4f))
        colors[list.hashCode().and(0x7FFFFFFF) % colors.size]
    }
    Box(
        modifier = Modifier
            .width(148.dp)
            .height(192.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MangaColors.SurfaceHigh)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(108.dp)) {
            if (list.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = list.coverUrl,
                    contentDescription = list.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colors = listOf(cardColor, MangaColors.SurfaceHigh)))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(colors = listOf(Color.Transparent, MangaColors.SurfaceHigh)))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = list.name,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    tint = MangaColors.Cyan,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${list.itemCount} عنصر",
                    color = MangaColors.Cyan,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// =====================================================================================
// Privacy
// =====================================================================================

@Composable
private fun PrivacySettingsSection(
    showListsPublic: Boolean,
    showActivityPublic: Boolean,
    onToggleLists: (Boolean) -> Unit,
    onToggleActivity: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(16.dp)
    ) {
        Text("الخصوصية", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        PrivacyRow(
            icon = Icons.Filled.Public,
            label = "إظهار القوائم للآخرين",
            description = "يمكن لأي شخص رؤية قوائمك العامة",
            checked = showListsPublic,
            onCheckedChange = onToggleLists
        )
        PrivacyRow(
            icon = Icons.Filled.History,
            label = "إظهار النشاط للآخرين",
            description = "يظهر نشاطك الأخير في ملفك العام",
            checked = showActivityPublic,
            onCheckedChange = onToggleActivity
        )
    }
}

@Composable
private fun PrivacyRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MangaColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MangaColors.Cyan,
                checkedTrackColor = MangaColors.CyanDim,
                uncheckedThumbColor = MangaColors.Muted,
                uncheckedTrackColor = MangaColors.SurfaceHigh
            )
        )
    }
}

// =====================================================================================
// Notifications
// =====================================================================================

@Composable
private fun NotificationsSection(
    notifications: List<CommunityNotification>,
    unreadCount: Int,
    onNotificationClick: (CommunityNotification) -> Unit
) {
    if (notifications.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("إشعارات المجتمع", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (unreadCount > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MangaColors.Pink)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(unreadCount.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        notifications.take(5).forEachIndexed { index, item ->
            NotificationCard(item = item, onClick = { onNotificationClick(item) })
            if (index < minOf(notifications.size, 5) - 1) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NotificationCard(item: CommunityNotification, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (item.read) MangaColors.SurfaceHigh else MangaColors.GlowPurple)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MangaColors.SurfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = MangaColors.PrimaryLight, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(item.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!item.read) {
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MangaColors.Cyan))
        }
    }
}

// =====================================================================================
// Moderation entry point
// =====================================================================================

@Composable
private fun ModerationEntryCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MangaColors.SurfaceContainer)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MangaColors.Yellow.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = MangaColors.Yellow, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("لوحة الإشراف", color = MangaColors.Yellow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text("إدارة البلاغات والمحتوى", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MangaColors.Muted,
            modifier = Modifier.size(16.dp)
        )
    }
}
