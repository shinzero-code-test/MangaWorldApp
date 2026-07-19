package com.exapps.mangaworld.presentation.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.exapps.mangaworld.domain.model.ModerationReport
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text("لوحة الإشراف", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(0.dp))
        }

        if (!canModerate) {
            Text("هذه الشاشة مخصصة للمشرفين فقط.", color = MangaColors.OnSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MangaColors.Yellow.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("لحل البلاغات أو اتخاذ إجراء، استخدم لوحة التحكم على الويب.", color = MangaColors.Yellow, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
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
            Text("بلاغ #${report.id.take(6)}", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Text(report.reason, color = MangaColors.OnSurfaceVariant)
            Text("الحالة: ${report.status}", color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
        }
    }
}
