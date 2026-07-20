
package com.exapps.mangaworld.presentation.community
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



@HiltViewModel
class ModerationDashboardViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {
    val reports = communityRepository.observeModerationReports().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profile = flow { emit(communityRepository.getCurrentProfile()) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun ModerationDashboardScreen(onBack: () -> Unit, onOpenDashboard: () -> Unit = {}, viewModel: ModerationDashboardViewModel = hiltViewModel()) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val canModerate = profile?.role in setOf("moderator", "super-admin")

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_back), tint = MangaColors.OnSurface) }
            Text(stringResource(R.string.moderation_title), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(0.dp))
        }

        if (!canModerate) {
            Text(stringResource(R.string.moderation_moderators_only), color = MangaColors.OnSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MangaColors.Yellow.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.moderation_use_dashboard), color = MangaColors.Yellow, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(reports, key = { it.id }) { report ->
                ModerationReportCard(report = report)
            }
        }
    }
}

@Composable
private fun ModerationReportCard(report: ModerationReport) {
    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.fmt_065, report.id.take(6)), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Text(report.reason, color = MangaColors.OnSurfaceVariant)
            Text(stringResource(R.string.fmt_049, report.status), color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
        }
    }
}

