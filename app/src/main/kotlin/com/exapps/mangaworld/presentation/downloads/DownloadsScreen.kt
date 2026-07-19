import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

    fun cancelTask(id: String) = viewModelScope.launch { manager.cancelTask(id) }
    fun retryTask(id: String) = viewModelScope.launch { manager.retryTask(id) }
    fun clearCompleted() = viewModelScope.launch { manager.clearCompleted() }
    fun pauseTask(id: String) = viewModelScope.launch { manager.pauseTask(id) }
    fun resumeTask(id: String) = viewModelScope.launch { manager.resumeTask(id) }

    fun pauseAll() = viewModelScope.launch { manager.pauseAll() }
    fun resumeAll() = viewModelScope.launch { manager.resumeAll() }
    fun cancelAll() {
        viewModelScope.launch {
            tasks.value.filter { it.status == "queued" || it.status == "running" }
                .forEach { manager.cancelTask(it.id) }
        }
    }
    fun cancelMangaDownloads(mangaId: String) {
        viewModelScope.launch {
            tasks.value.filter { it.mangaId == mangaId && (it.status == "queued" || it.status == "running" || it.status == "paused") }
                .forEach { manager.cancelTask(it.id) }
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val inProgress = remember(tasks) { tasks.filter { it.status == "running" } }
    val queued = remember(tasks) { tasks.filter { it.status == "queued" || it.status == "paused" } }
    val completed = remember(tasks) { tasks.filter { it.status == "completed" } }
    val failed = remember(tasks) { tasks.filter { it.status == "failed" || it.status == "cancelled" } }

    var showMenu by remember { mutableStateOf(false) }
    var showCancelAllDialog by remember { mutableStateOf(false) }

    val scrollBehavior = exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MangaColors.Background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.download_list),
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { /* handled by nav */ }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MangaColors.OnSurface
                        )
                    }
                },
                actions = {
                    if (tasks.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.options),
                                    tint = MangaColors.OnSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pause_all), color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.Pause, null, tint = MangaColors.Yellow) },
                                    onClick = { viewModel.pauseAll(); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.resume_all), color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = MangaColors.Cyan) },
                                    onClick = { viewModel.resumeAll(); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_all), color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.Cancel, null, tint = MangaColors.Error) },
                                    onClick = { showCancelAllDialog = true; showMenu = false }
                                )
                                HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.2f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_completed), color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, tint = MangaColors.Muted) },
                                    onClick = { viewModel.clearCompleted(); showMenu = false }
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MangaColors.Background,
                    scrolledContainerColor = MangaColors.Surface,
                    titleContentColor = MangaColors.OnSurface,
                    navigationIconContentColor = MangaColors.OnSurface,
                    actionIconContentColor = MangaColors.OnSurface
                )
            )
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }

        // Group tasks by mangaId
        val grouped = remember(tasks) {
            tasks.groupBy { it.mangaId }
                .toSortedMap(compareByDescending { mangaId ->
                    tasks.filter { it.mangaId == mangaId }
                        .maxOfOrNull { it.createdAt } ?: 0L
                })
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            grouped.forEach { (mangaId, mangaTasks) ->
                val mangaTitle = mangaTasks.firstOrNull()?.mangaTitle ?: mangaId
                val inProgress = mangaTasks.filter { it.status == "running" }
                val queued = mangaTasks.filter { it.status == "queued" || it.status == "paused" }
                val completed = mangaTasks.filter { it.status == "completed" }
                val failed = mangaTasks.filter { it.status == "failed" || it.status == "cancelled" }

                item("manga_header_$mangaId") {
                    MangaGroupHeader(
                        mangaTitle = mangaTitle,
                        totalChapters = mangaTasks.size,
                        completedCount = completed.size,
                        inProgressCount = inProgress.size,
                        queuedCount = queued.size,
                        failedCount = failed.size,
                        onCancelAll = { viewModel.cancelMangaDownloads(mangaId) }
                    )
                }

                // Show active tasks (in-progress + queued + paused)
                val activeTasks = inProgress + queued
                activeTasks.forEach { task ->
                    item("task_${task.id}") {
                        ChapterDownloadCard(
                            task = task,
                            onPause = if (task.status == "running") {{ viewModel.pauseTask(task.id) }} else null,
                            onResume = if (task.status == "paused") {{ viewModel.resumeTask(task.id) }} else null,
                            onCancel = { viewModel.cancelTask(task.id) }
                        )
                    }
                }

                // Show completed tasks (collapsed by default, expandable)
                if (completed.isNotEmpty()) {
                    item("completed_header_$mangaId") {
                        CompletedChaptersSummary(
                            count = completed.size,
                            onCancelAll = { completed.forEach { viewModel.cancelTask(it.id) } }
                        )
                    }
                }

                // Show failed tasks
                failed.forEach { task ->
                    item("task_${task.id}") {
                        ChapterDownloadCard(
                            task = task,
                            onPause = null,
                            onResume = null,
                            onCancel = null,
                            onRetry = { viewModel.retryTask(task.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCancelAllDialog) {
        AlertDialog(
            onDismissRequest = { showCancelAllDialog = false },
            containerColor = MangaColors.SurfaceContainer,
            titleContentColor = MangaColors.OnSurface,
            textContentColor = MangaColors.OnSurfaceVariant,
            title = { Text(stringResource(R.string.str_079)) },
            text = { Text(stringResource(R.string.str_437)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelAll(); showCancelAllDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MangaColors.Error)
                ) { Text(stringResource(R.string.clear_all)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelAllDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MangaColors.Muted)
                ) { Text(stringResource(R.string.back)) }
            }
        )
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MangaColors.Muted.copy(alpha = 0.5f)
            )
            Text(
                stringResource(R.string.library_empty_downloads),
                color = MangaColors.Muted,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.downloaded_chapters_appear_here),
                color = MangaColors.Muted.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ─── Manga Group Header ──────────────────────────────────────────────────────

@Composable
private fun MangaGroupHeader(
    mangaTitle: String,
    totalChapters: Int,
    completedCount: Int,
    inProgressCount: Int,
    queuedCount: Int,
    failedCount: Int,
    onCancelAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MangaColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (inProgressCount + queuedCount > 0) {
                    IconButton(onClick = onCancelAll, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.clear_all), modifier = Modifier.size(18.dp), tint = MangaColors.Muted)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inProgressCount > 0) StatusChip(stringResource(R.string.fmt_014, inProgressCount), MangaColors.Cyan)
                if (queuedCount > 0) StatusChip(stringResource(R.string.fmt_016, queuedCount), MangaColors.Yellow)
                if (completedCount > 0) StatusChip(stringResource(R.string.fmt_006, completedCount), MangaColors.Primary)
                if (failedCount > 0) StatusChip(stringResource(R.string.fmt_011, failedCount), MangaColors.Error)
            }
            // Progress bar
            val total = totalChapters.coerceAtLeast(1)
            val progress = completedCount.toFloat() / total
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MangaColors.Primary,
                trackColor = MangaColors.Background,
            )
            Text(
                stringResource(R.string.fmt_009, completedCount, totalChapters),
                style = MaterialTheme.typography.labelSmall,
                color = MangaColors.Muted
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

// ─── Chapter Download Card (compact) ──────────────────────────────────────────

@Composable
private fun ChapterDownloadCard(
    task: DownloadTaskEntity,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)? = null
) {
    val progress by animateFloatAsState(targetValue = task.progress.coerceIn(0f, 1f), label = "p")
    val statusColor = when (task.status) {
        "running" -> MangaColors.Cyan
        "paused" -> MangaColors.Yellow
        "completed" -> MangaColors.Primary
        "failed", "cancelled" -> MangaColors.Error
        else -> MangaColors.Yellow
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(10.dp))
            // Chapter info
            Column(Modifier.weight(1f)) {
                Text(
                    task.chapterTitle ?: task.chapterUrl.substringAfterLast("/"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.status == "running" && task.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = MangaColors.Cyan,
                        trackColor = MangaColors.SurfaceContainer,
                    )
                }
            }
            // Status text
            Text(
                when (task.status) {
                    "queued" -> stringResource(R.string.pending)
                    "running" -> "${(progress * 100).toInt()}%"
                    "paused" -> stringResource(R.string.stopped)
                    "completed" -> "✓"
                    "failed" -> stringResource(R.string.str_331)
                    "cancelled" -> stringResource(R.string.cancelled)
                    else -> task.status
                },
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
            // Action buttons
            if (onPause != null) {
                IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Pause, stringResource(R.string.pause), modifier = Modifier.size(16.dp), tint = MangaColors.Yellow)
                }
            }
            if (onResume != null) {
                IconButton(onClick = onResume, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.resume), modifier = Modifier.size(16.dp), tint = MangaColors.Cyan)
                }
            }
            if (onRetry != null) {
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.retry_short), modifier = Modifier.size(16.dp), tint = MangaColors.Cyan)
                }
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cancel), modifier = Modifier.size(16.dp), tint = MangaColors.Muted)
                }
            }
        }
    }
}

// ─── Completed Chapters Summary ───────────────────────────────────────────────

@Composable
private fun CompletedChaptersSummary(count: Int, onCancelAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CheckCircle, null, tint = MangaColors.Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.fmt_007, count),
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.Primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    if (expanded) stringResource(R.string.collapse) else stringResource(R.string.show_alt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Muted
                )
            }
        }
    }
}


