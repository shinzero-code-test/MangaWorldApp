package com.exapps.mangaworld.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {
    val profile = kotlinx.coroutines.flow.flow { emit(communityRepository.getCurrentProfile()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val notifications = communityRepository.observeNotifications(20)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun markRead(id: String) {
        viewModelScope.launch { runCatching { communityRepository.markNotificationRead(id) } }
    }
}

@Composable
fun UserProfileScreen(
    onOpenCloudSync: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCommunityChat: () -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الملف الشخصي", style = MaterialTheme.typography.headlineMedium, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .background(MangaColors.GlowPurple, CircleShape)
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.VerifiedUser, null, tint = MangaColors.PrimaryLight)
                }
                Text(profile?.username ?: "Guest", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(profile?.badgeLabel ?: "Beginner", color = MangaColors.Cyan, style = MaterialTheme.typography.bodyMedium)
                if (!profile?.bio.isNullOrBlank()) {
                    Text(profile?.bio.orEmpty(), color = MangaColors.OnSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenCloudSync) { Icon(Icons.Filled.CloudSync, null); Text("السحابة") }
                    OutlinedButton(onClick = onOpenDiagnostics) { Icon(Icons.Filled.Settings, null); Text("التشخيص") }
                }
                OutlinedButton(onClick = onOpenCommunityChat) { Text("الدردشة المباشرة") }
            }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("إشعارات المجتمع", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                if (notifications.isEmpty()) {
                    Text("لا توجد إشعارات حتى الآن", color = MangaColors.OnSurfaceVariant)
                } else {
                    notifications.forEach { item ->
                        NotificationCard(item = item, onClick = { viewModel.markRead(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(item: CommunityNotification, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (item.read) MangaColors.Surface else MangaColors.GlowPurple),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
            Text(item.body, color = MangaColors.OnSurfaceVariant)
        }
    }
}
