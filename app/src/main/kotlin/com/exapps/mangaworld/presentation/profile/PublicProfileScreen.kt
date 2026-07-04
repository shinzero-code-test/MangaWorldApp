package com.exapps.mangaworld.presentation.profile

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.Stable
import javax.inject.Inject

@Stable
data class PublicProfileUiState(
    val profile: CommunityProfile? = null,
    val lists: List<CustomUserList> = emptyList(),
    val activity: List<CommunityComment> = emptyList()
)

@HiltViewModel
class PublicProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    communityRepository: CommunityRepository,
    sessionManager: com.exapps.mangaworld.core.firebase.FirebaseSessionManager
) : ViewModel() {
    private val userId: String = checkNotNull(savedStateHandle["userId"])

    val isOwnProfile: Boolean = userId == sessionManager.currentUserId()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

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

    fun toggleFollow() {
        _isFollowing.value = !_isFollowing.value
    }
}

@Composable
fun PublicProfileScreen(onBack: () -> Unit, viewModel: PublicProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val profile = state.profile
    val isOwnProfile = viewModel.isOwnProfile

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MangaColors.Background),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            PublicProfileHeader(profile = profile, onBack = onBack)
        }

        // Only show follow button if NOT viewing own profile
        if (!isOwnProfile) {
            item {
                FollowButton(
                    isFollowing = isFollowing,
                    onClick = { viewModel.toggleFollow() }
                )
            }
        }

        if (state.lists.isNotEmpty()) {
            item {
                PublicListsSection(lists = state.lists)
            }
        }

        if (state.activity.isNotEmpty()) {
            item {
                Text(
                    "النشاط الأخير",
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            items(state.activity, key = { it.id }) { comment ->
                ActivityCommentCard(comment = comment)
            }
        }

        if (state.lists.isEmpty() && state.activity.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = MangaColors.Muted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "هذا الملف لا يحتوي على محتوى عام",
                        color = MangaColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PublicProfileHeader(
    profile: CommunityProfile?,
    onBack: () -> Unit
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
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MangaColors.OnSurface)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MangaColors.GlowPurple)
                ) {
                    if (!profile?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profile.avatarUrl,
                            contentDescription = "صورة الملف الشخصي",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = (profile?.username ?: "U").take(1).uppercase(),
                            color = MangaColors.PrimaryLight,
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(top = 12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = profile?.username ?: "مستخدم",
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
}

@Composable
private fun FollowButton(isFollowing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        if (isFollowing) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.PersonRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("إلغاء المتابعة", fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MangaColors.Cyan),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("متابعة", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PublicListsSection(lists: List<CustomUserList>) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "القوائم العامة",
            color = MangaColors.OnSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(lists, key = { it.id }) { list ->
                PublicListCard(list = list)
            }
        }
    }
}

@Composable
private fun PublicListCard(list: CustomUserList) {
    val cardColor = remember(list.id) {
        val colors = listOf(MangaColors.PrimaryDim, MangaColors.CyanDim, MangaColors.Pink.copy(alpha = 0.5f), MangaColors.Orange.copy(alpha = 0.5f), MangaColors.Green.copy(alpha = 0.4f))
        colors[list.hashCode().and(0x7FFFFFFF) % colors.size]
    }
    Card(
        modifier = Modifier.width(140.dp).height(170.dp),
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
private fun ActivityCommentCard(comment: CommunityComment) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    comment.authorBadge,
                    color = MangaColors.Cyan,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                Text(
                    comment.authorName,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                text = comment.text,
                color = MangaColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
