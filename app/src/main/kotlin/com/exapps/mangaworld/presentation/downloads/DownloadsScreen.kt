package com.exapps.mangaworld.presentation.downloads

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val manager: DownloadQueueManager
) : ViewModel() {

    val tasks = manager.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancelTask(id: String)   = viewModelScope.launch { manager.cancelTask(id) }
    fun retryTask(id: String)    = viewModelScope.launch { manager.retryTask(id) }
    fun clearCompleted()         = viewModelScope.launch { manager.clearCompleted() }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val queued     = remember(tasks) { tasks.filter { it.status == "queued" } }
    val inProgress = remember(tasks) { tasks.filter { it.status == "running" } }
    val failed     = remember(tasks) { tasks.filter { it.status == "failed" || it.status == "cancelled" } }
    val completed  = remember(tasks) { tasks.filter { it.status == "completed" } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("قائمة التنزيلات", style = MaterialTheme.typography.titleLarge,
                color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            if (completed.isNotEmpty()) {
                TextButton(onClick = viewModel::clearCompleted) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp),
                        tint = MangaColors.Muted)
                    Spacer(Modifier.width(4.dp))
                    Text("مسح المكتملة", color = MangaColors.Muted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.CloudDownload, null,
                        modifier = Modifier.size(64.dp), tint = MangaColors.Muted)
                    Text("لا توجد تنزيلات", color = MangaColors.Muted,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp)) {

            // ── In Progress ──────────────────────────────────────────────────
            if (inProgress.isNotEmpty()) {
                item { SectionHeader("جاري التنزيل", inProgress.size, MangaColors.Cyan) }
                items(inProgress, key = { it.id }) { task ->
                    TaskCard(task,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry  = null)
                }
            }

            // ── Queued ───────────────────────────────────────────────────────
            if (queued.isNotEmpty()) {
                item { SectionHeader("في الانتظار", queued.size, MangaColors.Yellow) }
                items(queued, key = { it.id }) { task ->
                    TaskCard(task,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry  = null)
                }
            }

            // ── Failed / Cancelled ────────────────────────────────────────────
            if (failed.isNotEmpty()) {
                item { SectionHeader("فشل / ملغي", failed.size, MaterialTheme.colorScheme.error) }
                items(failed, key = { it.id }) { task ->
                    TaskCard(task,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry  = { viewModel.retryTask(task.id) })
                }
            }

            // ── Completed ─────────────────────────────────────────────────────
            if (completed.isNotEmpty()) {
                item { SectionHeader("مكتملة", completed.size, MangaColors.Primary) }
                items(completed, key = { it.id }) { task ->
                    TaskCard(task, onCancel = null, onRetry = null)
                }
            }
        }
    }
}

// ─── Components ───────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text("$title ($count)", style = MaterialTheme.typography.labelLarge,
            color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TaskCard(
    task: DownloadTaskEntity,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?
) {
    val statusColor by animateColorAsState(
        when (task.status) {
            "running"   -> MangaColors.Cyan
            "completed" -> MangaColors.Primary
            "failed", "cancelled" -> MaterialTheme.colorScheme.error
            else        -> MangaColors.Yellow
        }, label = "statusColor"
    )

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Title + action row
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when (task.status) {
                        "running"   -> Icons.Filled.CloudDownload
                        "completed" -> Icons.Filled.CheckCircle
                        "failed", "cancelled" -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.HourglassEmpty
                    },
                    null, modifier = Modifier.size(20.dp), tint = statusColor
                )
                Text(
                    task.chapterTitle ?: task.chapterUrl.substringAfterLast("/"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MangaColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (onRetry != null) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "إعادة المحاولة",
                            modifier = Modifier.size(18.dp), tint = MangaColors.Cyan)
                    }
                }
                if (onCancel != null) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "إلغاء",
                            modifier = Modifier.size(18.dp), tint = MangaColors.Muted)
                    }
                }
            }

            // Progress bar (only for active)
            if (task.status in listOf("running", "queued")) {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = statusColor,
                    trackColor = MangaColors.SurfaceContainer
                )
            }

            // Status text row
            Row(horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (task.status) {
                        "queued"    -> "في الانتظار"
                        "running"   -> "جاري التنزيل • ${task.downloadedPages}/${task.totalPages} صفحة"
                        "completed" -> "مكتمل • ${task.totalPages} صفحة"
                        "failed"    -> "فشل"
                        "cancelled" -> "ملغي"
                        else        -> task.status
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Muted
                )
                if (task.status == "running" && task.totalPages > 0) {
                    Text(
                        "${(task.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Error message
            if (task.errorMessage != null && task.status == "failed") {
                Text(
                    task.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
