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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.updateAvatarUri(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
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
            onNotificationClick = { viewModel.markRead(it.id) }
        )

        if (profile?.role in setOf("moderator", "admin")) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable(onClick = onOpenModeration),
                colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Tune, null, tint = MangaColors.Yellow, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("لوحة الإشراف", color = MangaColors.Yellow, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profile: CommunityProfile?,
    avatarUri: Uri?,
    onAvatarClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(MangaColors.PrimaryDim.copy(alpha = 0.4f), MangaColors.Background)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MangaColors.GlowPurple)
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
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(top = 12.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onAvatarClick,
                    modifier = Modifier
                        .size(32.dp)
                        .background(MangaColors.Cyan, CircleShape)
                        .padding(2.dp)
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "تغيير الصورة",
                        tint = MangaColors.Background,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = profile?.username ?: "ضيف",
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            if (!profile?.badgeLabel.isNullOrBlank()) {
                Text(
                    text = profile.badgeLabel,
                    color = MangaColors.Cyan,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(MangaColors.GlowCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            if (!profile?.bio.isNullOrBlank()) {
                Text(
                    text = profile.bio,
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatsRow(
    totalReadingTimeMs: Long,
    chaptersRead: Int,
    streak: Int
) {
    val hours = (totalReadingTimeMs / 3_600_000).toInt()
    val mins = ((totalReadingTimeMs % 3_600_000) / 60_000).toInt()
    val readingTimeText = if (hours > 0) "${hours}س ${mins}د" else "${mins}د"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(modifier = Modifier.weight(1f), value = readingTimeText, label = "وقت القراءة")
        StatCard(modifier = Modifier.weight(1f), value = chaptersRead.toString(), label = "فصول مقروءة")
        StatCard(modifier = Modifier.weight(1f), value = "$streak", label = "أيام متتالية")
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = MangaColors.Cyan,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = label,
                color = MangaColors.OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    onOpenCloudSync: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCommunityChat: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenReadingStats: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("إجراءات سريعة", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionItem(Icons.Filled.CloudSync, "السحابة", MangaColors.Cyan, onOpenCloudSync)
                QuickActionItem(Icons.Filled.Tune, "التشخيص", MangaColors.Pink, onOpenDiagnostics)
                QuickActionItem(Icons.Filled.Chat, "الدردشة", MangaColors.Green, onOpenCommunityChat)
                QuickActionItem(Icons.Filled.Notifications, "إشعارات", MangaColors.Yellow, onOpenNotifications)
                QuickActionItem(Icons.Filled.Speed, "إحصائيات", MangaColors.Orange, onOpenReadingStats)
            }
        }
    }
}

@Composable
private fun QuickActionItem(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(tint.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun CustomListsSection(
    lists: List<CustomUserList>,
    onOpenLists: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("قوائمي", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(
                    "عرض الكل",
                    color = MangaColors.Cyan,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onOpenLists)
                )
            }
            Spacer(Modifier.height(12.dp))
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
}

@Composable
private fun ListCardItem(list: CustomUserList, onClick: () -> Unit) {
    val cardColor = remember(list.id) {
        val colors = listOf(MangaColors.PrimaryDim, MangaColors.CyanDim, MangaColors.Pink.copy(alpha = 0.5f), MangaColors.Orange.copy(alpha = 0.5f), MangaColors.Green.copy(alpha = 0.4f))
        colors[list.hashCode().and(0x7FFFFFFF) % colors.size]
    }
    Card(
        modifier = Modifier
            .width(140.dp)
            .height(170.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceHigh)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(cardColor, MangaColors.SurfaceHigh)
                        )
                    )
            )
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
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        tint = MangaColors.Cyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "${list.itemCount} عنصر",
                        color = MangaColors.Cyan,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacySettingsSection(
    showListsPublic: Boolean,
    showActivityPublic: Boolean,
    onToggleLists: (Boolean) -> Unit,
    onToggleActivity: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("الخصوصية", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            PrivacyRow(
                label = "إظهار القوائم للآخرين",
                checked = showListsPublic,
                onCheckedChange = onToggleLists
            )
            PrivacyRow(
                label = "إظهار النشاط للآخرين",
                checked = showActivityPublic,
                onCheckedChange = onToggleActivity
            )
        }
    }
}

@Composable
private fun PrivacyRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun NotificationsSection(
    notifications: List<CommunityNotification>,
    onNotificationClick: (CommunityNotification) -> Unit
) {
    if (notifications.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("إشعارات المجتمع", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            notifications.take(5).forEach { item ->
                NotificationCard(item = item, onClick = { onNotificationClick(item) })
            }
        }
    }
}

@Composable
private fun NotificationCard(item: CommunityNotification, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (item.read) MangaColors.SurfaceHigh else MangaColors.GlowPurple),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(item.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
