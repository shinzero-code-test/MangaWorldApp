package com.exapps.mangaworld.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    communityRepository: CommunityRepository
) : ViewModel() {
    val role = kotlinx.coroutines.flow.flow { emit(communityRepository.getCurrentProfile()?.role ?: "viewer") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "viewer")
}

private data class MoreGridItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenDownloads: () -> Unit,
    onOpenLocalStorage: () -> Unit,
    onOpenReadingStats: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenSuggestions: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    viewModel: MoreViewModel = hiltViewModel()
) {
    val role by viewModel.role.collectAsStateWithLifecycle()
    val canModerate = role in setOf("moderator", "super-admin")

    val gridItems = buildList {
        add(MoreGridItem(Icons.Filled.Download, "التنزيلات", "الفصول المنزّلة", MangaColors.Cyan, onOpenDownloads))
        add(MoreGridItem(Icons.Filled.FolderOpen, "المحلي", "المانجا المحفوظة", MangaColors.GlowPurple, onOpenLocalStorage))
        add(MoreGridItem(Icons.Filled.AutoAwesome, "اقتراحات", "مانجا قد تعجبك", MangaColors.Yellow, onOpenSuggestions))
        add(MoreGridItem(Icons.Filled.BarChart, "الإحصائيات", "وقت القراءة", MangaColors.Pink, onOpenReadingStats))
        add(MoreGridItem(Icons.Filled.EmojiEvents, "الإنجازات", "تتبع التقدم", MangaColors.Yellow, onOpenGoals))
        add(MoreGridItem(Icons.Filled.Cloud, "المزامنة", "البيانات السحابية", MangaColors.Cyan, onOpenCloudSync))
        add(MoreGridItem(Icons.Filled.Tune, "المصادر", "إدارة مصادر المانجا", MangaColors.Green, onOpenSources))
        if (canModerate) {
            add(MoreGridItem(Icons.Filled.Shield, "لوحة الإشراف", "إدارة المحتوى", MangaColors.Yellow, onOpenModeration))
        }
        add(MoreGridItem(Icons.Filled.Person, "الملف الشخصي", "حسابك وبياناتك", MangaColors.Cyan, onOpenProfile))
        add(MoreGridItem(Icons.Filled.Settings, "الإعدادات", "تخصيص التطبيق", MangaColors.Muted, onOpenSettings))
        add(MoreGridItem(Icons.Filled.BugReport, "التشخيص", "معلومات تقنية", MangaColors.Orange, onOpenDiagnostics))
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("المزيد", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(gridItems) { item ->
                MoreGridCard(item)
            }
        }
    }
}

@Composable
private fun MoreGridCard(item: MoreGridItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { item.onClick() },
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(item.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = item.color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}
