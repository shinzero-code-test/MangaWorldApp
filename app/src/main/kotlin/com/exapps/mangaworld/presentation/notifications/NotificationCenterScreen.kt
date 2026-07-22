package com.exapps.mangaworld.presentation.notifications

import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Unified notification item — covers both community and local notifications. */
data class UnifiedNotification(
    val id: String,
    val title: String,
    val body: String,
    val type: String,           // "community", "chapter_update", "suggestion", "reminder"
    val mangaId: String? = null,
    val read: Boolean = false,
    val timestamp: Long = 0L,
    val icon: String = "notifications"  // icon key for display
)

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    private val _unreadOnly = MutableStateFlow(false)
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()

    /** Community notifications from Firestore */
    private val communityNotifications = communityRepository.observeNotifications(100)
        .map { items ->
            items.map { notif ->
                UnifiedNotification(
                    id = notif.id,
                    title = notif.title,
                    body = notif.body,
                    type = when (notif.type) {
                        CommunityNotificationType.REPLY -> "reply"
                        CommunityNotificationType.MENTION -> "mention"
                        CommunityNotificationType.REVIEW_REACTION -> "reaction"
                        CommunityNotificationType.COMMENT_THREAD -> "thread"
                        CommunityNotificationType.CHAT_MENTION -> "chat"
                        CommunityNotificationType.SYSTEM_ALERT -> "system"
                    },
                    mangaId = notif.mangaId,
                    read = notif.read,
                    timestamp = notif.createdAt
                )
            }
        }

    private val _refreshTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Local notifications from SharedPreferences (chapter updates, suggestions, reminders) */
    private val localNotifications = _refreshTrigger.flatMapLatest {
        kotlinx.coroutines.flow.flow {
            val prefs = context.getSharedPreferences("local_notifications", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("notifications", "[]") ?: "[]"
            val items = try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    UnifiedNotification(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", ""),
                        body = obj.optString("body", ""),
                        type = obj.optString("type", "system"),
                        mangaId = obj.optString("mangaId", null),
                        read = obj.optBoolean("read", false),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                }
            } catch (_: Exception) { emptyList() }
            emit(items)
        }
    }

    val notifications = combine(communityNotifications, localNotifications, _unreadOnly) { community, local, unread ->
        val all = (community + local).sortedByDescending { it.timestamp }
        if (unread) all.filter { !it.read } else all
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val unreadCount = notifications
        .map { items -> items.count { !it.read } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun toggleUnreadOnly() { _unreadOnly.value = !_unreadOnly.value }

    fun markRead(id: String) {
        viewModelScope.launch {
            runCatching { communityRepository.markNotificationRead(id) }
            // Also mark local notifications as read in SharedPreferences
            val prefs = context.getSharedPreferences("local_notifications", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("notifications", "[]") ?: "[]"
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id", "") == id) {
                    obj.put("read", true)
                    changed = true
                }
            }
            if (changed) {
                prefs.edit().putString("notifications", arr.toString()).apply()
                _refreshTrigger.tryEmit(Unit)
            }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            notifications.value.forEach { item ->
                if (!item.read) {
                    runCatching { communityRepository.markNotificationRead(item.id) }
                    // Also mark local notifications
                    val prefs = context.getSharedPreferences("local_notifications", android.content.Context.MODE_PRIVATE)
                    val json = prefs.getString("notifications", "[]") ?: "[]"
                    val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optString("id", "") == item.id) obj.put("read", true)
                    }
                    prefs.edit().putString("notifications", arr.toString()).apply()
                    _refreshTrigger.tryEmit(Unit)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onNotificationClick: (UnifiedNotification) -> Unit,
    viewModel: NotificationCenterViewModel = hiltViewModel()
) {
    val items by viewModel.notifications.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_center), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(onClick = viewModel::markAllRead) {
                            Text(stringResource(R.string.read_all), color = MangaColors.Cyan, style = MaterialTheme.typography.labelMedium)
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
                    label = { Text(stringResource(R.string.browse_all)) },
                    shape = RoundedCornerShape(10.dp)
                )
                FilterChip(
                    selected = unreadOnly,
                    onClick = { if (!unreadOnly) viewModel.toggleUnreadOnly() },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.unread))
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
                            if (unreadOnly) stringResource(R.string.no_unread_notifications) else stringResource(R.string.no_notifications),
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
                                onNotificationClick(item)
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
    notification: UnifiedNotification,
    onClick: () -> Unit
) {
    val typeIcon = when (notification.type) {
        "reply" -> Icons.Filled.Reply
        "mention" -> Icons.Filled.AlternateEmail
        "reaction" -> Icons.Filled.Star
        "thread" -> Icons.Filled.Forum
        "chat" -> Icons.Filled.Chat
        "system" -> Icons.Filled.Info
        "chapter_update" -> Icons.Filled.NewReleases
        "suggestion" -> Icons.Filled.AutoAwesome
        "reminder" -> Icons.Filled.Timer
        else -> Icons.Filled.Notifications
    }

    val typeColor = when (notification.type) {
        "reply" -> MangaColors.Cyan
        "mention" -> MangaColors.Primary
        "reaction" -> MangaColors.Yellow
        "thread" -> MangaColors.Green
        "chat" -> MangaColors.Orange
        "system" -> MangaColors.Muted
        "chapter_update" -> MangaColors.Cyan
        "suggestion" -> MangaColors.Yellow
        "reminder" -> MangaColors.Pink
        else -> MangaColors.Muted
    }

    val typeLabel = when (notification.type) {
        "reply" -> stringResource(R.string.community_reply)
        "mention" -> stringResource(R.string.bookmark)
        "reaction" -> stringResource(R.string.interact)
        "thread" -> stringResource(R.string.discussion_alt)
        "chat" -> stringResource(R.string.conversation)
        "system" -> stringResource(R.string.alert)
        "chapter_update" -> stringResource(R.string.home_latest)
        "suggestion" -> stringResource(R.string.more_suggestions)
        "reminder" -> stringResource(R.string.settings_notifications)
        else -> stringResource(R.string.notifications)
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
