import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityNotificationType
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {
    private val _unreadOnly = MutableStateFlow(false)
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()
    val notifications = combine(communityRepository.observeNotifications(100), _unreadOnly) { items, unread ->
        if (unread) items.filter { !it.read } else items
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val unreadCount = communityRepository.observeNotifications(100)
        .combine(_unreadOnly) { items, _ -> items.count { !it.read } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun toggleUnreadOnly() { _unreadOnly.value = !_unreadOnly.value }

    fun markRead(id: String) {
        viewModelScope.launch { runCatching { communityRepository.markNotificationRead(id) } }
    }

    fun markAllRead() {
        viewModelScope.launch {
            notifications.value.forEach { item ->
                if (!item.read) runCatching { communityRepository.markNotificationRead(item.id) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onOpenThread: (CommunityNotification) -> Unit,
    viewModel: NotificationCenterViewModel = hiltViewModel()
) {
    val items by viewModel.notifications.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("مركز الإشعارات", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(onClick = viewModel::markAllRead) {
                            Text("قراءة الكل", color = MangaColors.Cyan, style = MaterialTheme.typography.labelMedium)
                        }
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
        ) {
            // Filter chips
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !unreadOnly,
                    onClick = { if (unreadOnly) viewModel.toggleUnreadOnly() },
                    label = { Text("الكل") },
                    shape = RoundedCornerShape(10.dp)
                )
                FilterChip(
                    selected = unreadOnly,
                    onClick = { if (!unreadOnly) viewModel.toggleUnreadOnly() },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("غير المقروء")
                            if (unreadCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier.size(18.dp).clip(CircleShape).background(MangaColors.Primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$unreadCount", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )
            }

            if (items.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.NotificationsOff,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MangaColors.Muted.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (unreadOnly) "لا توجد إشعارات غير مقروءة" else "لا توجد إشعارات حالياً",
                            color = MangaColors.Muted,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        NotificationCard(
                            notification = item,
                            onClick = {
                                viewModel.markRead(item.id)
                                onOpenThread(item)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: CommunityNotification,
    onClick: () -> Unit
) {
    val typeIcon = when (notification.type) {
        CommunityNotificationType.REPLY -> Icons.Filled.Reply
        CommunityNotificationType.MENTION -> Icons.Filled.AlternateEmail
        CommunityNotificationType.REVIEW_REACTION -> Icons.Filled.Star
        CommunityNotificationType.COMMENT_THREAD -> Icons.Filled.Forum
        CommunityNotificationType.CHAT_MENTION -> Icons.Filled.Chat
        CommunityNotificationType.SYSTEM_ALERT -> Icons.Filled.Info
    }

    val typeColor = when (notification.type) {
        CommunityNotificationType.REPLY -> MangaColors.Cyan
        CommunityNotificationType.MENTION -> MangaColors.Primary
        CommunityNotificationType.REVIEW_REACTION -> MangaColors.Yellow
        CommunityNotificationType.COMMENT_THREAD -> MangaColors.Green
        CommunityNotificationType.CHAT_MENTION -> MangaColors.Orange
        CommunityNotificationType.SYSTEM_ALERT -> MangaColors.Muted
    }

    val typeLabel = when (notification.type) {
        CommunityNotificationType.REPLY -> stringResource(R.string.community_reply)
        CommunityNotificationType.MENTION -> "إشارة"
        CommunityNotificationType.REVIEW_REACTION -> "تفاعل"
        CommunityNotificationType.COMMENT_THREAD -> "مناقشة"
        CommunityNotificationType.CHAT_MENTION -> "محادثة"
        CommunityNotificationType.SYSTEM_ALERT -> "تنبيه"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.read) MangaColors.SurfaceContainer else MangaColors.GlowPurple
        )
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Type icon
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(typeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(18.dp))
            }

            // Content
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        notification.title,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Unread dot
                    if (!notification.read) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MangaColors.Primary))
                    }
                }
                Text(
                    notification.body,
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Type badge + time
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.background(typeColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(typeLabel, color = typeColor, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
