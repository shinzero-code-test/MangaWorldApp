package com.exapps.mangaworld.presentation.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.CommunityNotification
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

    fun toggleUnreadOnly() { _unreadOnly.value = !_unreadOnly.value }

    fun markRead(id: String) {
        viewModelScope.launch { runCatching { communityRepository.markNotificationRead(id) } }
    }
}

@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onOpenThread: (CommunityNotification) -> Unit,
    viewModel: NotificationCenterViewModel = hiltViewModel()
) {
    val items by viewModel.notifications.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text("مركز الإشعارات", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            FilterChip(selected = unreadOnly, onClick = viewModel::toggleUnreadOnly, label = { Text("غير المقروء") })
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.markRead(item.id)
                        onOpenThread(item)
                    },
                    colors = CardDefaults.cardColors(containerColor = if (item.read) MangaColors.SurfaceContainer else MangaColors.GlowPurple),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                        Text(item.body, color = MangaColors.OnSurfaceVariant)
                        Text(item.type.name, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Spacer(Modifier.padding(0.dp))
                    Text("لا توجد إشعارات حالياً", color = MangaColors.OnSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
