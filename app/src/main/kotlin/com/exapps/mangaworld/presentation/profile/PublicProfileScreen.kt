package com.exapps.mangaworld.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PublicProfileUiState(
    val profile: CommunityProfile? = null,
    val lists: List<CustomUserList> = emptyList(),
    val activity: List<CommunityComment> = emptyList()
)

@HiltViewModel
class PublicProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    communityRepository: CommunityRepository
) : ViewModel() {
    private val userId: String = checkNotNull(savedStateHandle["userId"])
    val state = combine(
        communityRepository.observePublicProfile(userId),
        communityRepository.observePublicLists(userId),
        communityRepository.observePublicActivity(userId)
    ) { profile, lists, activity ->
        PublicProfileUiState(
            profile = profile,
            lists = if (profile?.showListsPublic == true) lists else emptyList(),
            activity = if (profile?.showActivityPublic == true) activity else emptyList()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PublicProfileUiState())
}

@Composable
fun PublicProfileScreen(onBack: () -> Unit, viewModel: PublicProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MangaColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
                Text("الملف العام", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.padding(0.dp))
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.background(MangaColors.GlowPurple, CircleShape).padding(18.dp)) {
                        Icon(Icons.Filled.VerifiedUser, null, tint = MangaColors.PrimaryLight)
                    }
                    Text(profile?.username ?: "User", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(profile?.badgeLabel ?: "Beginner", color = MangaColors.Cyan)
                    if (!profile?.bio.isNullOrBlank()) Text(profile?.bio.orEmpty(), color = MangaColors.OnSurfaceVariant)
                }
            }
        }
        if (profile?.showListsPublic == true) {
            item { Text("القوائم العامة", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) }
            items(state.lists, key = { it.id }) { list ->
                Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(list.name, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                        if (list.description.isNotBlank()) Text(list.description, color = MangaColors.OnSurfaceVariant)
                        Text("${list.itemCount} عنصر", color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (profile?.showActivityPublic == true) {
            item { Text("النشاط العام", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) }
            items(state.activity, key = { it.id }) { comment ->
                Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(comment.text, color = MangaColors.OnSurfaceVariant)
                        Text(comment.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
