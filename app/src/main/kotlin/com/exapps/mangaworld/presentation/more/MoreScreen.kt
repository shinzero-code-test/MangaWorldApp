
package com.exapps.mangaworld.presentation.more
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



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
        add(MoreGridItem(Icons.Filled.Download, stringResource(R.string.more_downloads), stringResource(R.string.downloaded_chapters), MangaColors.Cyan, onOpenDownloads))
        add(MoreGridItem(Icons.Filled.FolderOpen, stringResource(R.string.more_local), stringResource(R.string.more_local_subtitle), MangaColors.GlowPurple, onOpenLocalStorage))
        add(MoreGridItem(Icons.Filled.AutoAwesome, stringResource(R.string.more_suggestions), stringResource(R.string.manga_you_may_like), MangaColors.Yellow, onOpenSuggestions))
        add(MoreGridItem(Icons.Filled.BarChart, stringResource(R.string.more_stats), stringResource(R.string.reading_time), MangaColors.Pink, onOpenReadingStats))
        add(MoreGridItem(Icons.Filled.EmojiEvents, stringResource(R.string.more_goals), stringResource(R.string.track_progress), MangaColors.Yellow, onOpenGoals))
        add(MoreGridItem(Icons.Filled.Cloud, stringResource(R.string.more_sync), stringResource(R.string.cloud_data), MangaColors.Cyan, onOpenCloudSync))
        add(MoreGridItem(Icons.Filled.Tune, stringResource(R.string.more_sources), stringResource(R.string.str_046), MangaColors.Green, onOpenSources))
        if (canModerate) {
            add(MoreGridItem(Icons.Filled.Shield, stringResource(R.string.moderation_title), stringResource(R.string.more_moderation_subtitle), MangaColors.Yellow, onOpenModeration))
        }
        add(MoreGridItem(Icons.Filled.Person, stringResource(R.string.more_profile), stringResource(R.string.account_data), MangaColors.Cyan, onOpenProfile))
        add(MoreGridItem(Icons.Filled.Settings, stringResource(R.string.more_settings), stringResource(R.string.customize_app), MangaColors.Muted, onOpenSettings))
        add(MoreGridItem(Icons.Filled.BugReport, stringResource(R.string.more_diagnostics), stringResource(R.string.technical_info), MangaColors.Orange, onOpenDiagnostics))
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_title), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
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
