package com.exapps.mangaworld.presentation.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    fun pauseAll() { /* TODO: Implement pause all via WorkManager */ }
    fun resumeAll() { /* TODO: Implement resume all via WorkManager */ }
    fun cancelAll() {
        viewModelScope.launch {
            tasks.value.filter { it.status == "queued" || it.status == "running" }
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
    val queued = remember(tasks) { tasks.filter { it.status == "queued" } }
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
                        "قائمة التنزيلات",
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { /* handled by nav */ }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
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
                                    contentDescription = "خيارات",
                                    tint = MangaColors.OnSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("إيقاف الكل", color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.Pause, null, tint = MangaColors.Yellow) },
                                    onClick = { viewModel.pauseAll(); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("استئناف الكل", color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = MangaColors.Cyan) },
                                    onClick = { viewModel.resumeAll(); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("إلغاء الكل", color = MangaColors.OnSurface) },
                                    leadingIcon = { Icon(Icons.Filled.Cancel, null, tint = MangaColors.Error) },
                                    onClick = { showCancelAllDialog = true; showMenu = false }
                                )
                                HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.2f))
                                DropdownMenuItem(
                                    text = { Text("مسح المكتملة", color = MangaColors.OnSurface) },
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

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (inProgress.isNotEmpty()) {
                item("section_in_progress") {
                    SectionHeader("جاري التنزيل", inProgress.size, MangaColors.Cyan)
                }
                items(inProgress, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = { /* TODO: implement */ },
                        onResume = { /* TODO: implement */ },
                        onSkip = null,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry = null,
                        onExpand = { /* expand handled inside card */ }
                    )
                }
            }

            if (queued.isNotEmpty()) {
                item("section_queued") {
                    SectionHeader("في الانتظار", queued.size, MangaColors.Yellow)
                }
                items(queued, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = null,
                        onResume = null,
                        onSkip = null,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry = null,
                        onExpand = { /* expand handled inside card */ }
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item("section_completed") {
                    SectionHeader("مكتملة", completed.size, MangaColors.Primary)
                }
                items(completed, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = null,
                        onResume = null,
                        onSkip = null,
                        onCancel = null,
                        onRetry = null,
                        onExpand = { /* expand handled inside card */ }
                    )
                }
            }

            if (failed.isNotEmpty()) {
                item("section_failed") {
                    SectionHeader("فشل", failed.size, MangaColors.Error)
                }
                items(failed, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = null,
                        onResume = null,
                        onSkip = null,
                        onCancel = null,
                        onRetry = { viewModel.retryTask(task.id) },
                        onExpand = { /* expand handled inside card */ }
                    )
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
            title = { Text("إلغاء جميع التنزيلات") },
            text = { Text("هل تريد إلغاء جميع التنزيلات الجارية وفي الانتظار؟") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelAll(); showCancelAllDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MangaColors.Error)
                ) { Text("إلغاء الكل") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelAllDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MangaColors.Muted)
                ) { Text("رجوع") }
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
                "لا توجد تنزيلات",
                color = MangaColors.Muted,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "سيظهر هنا أي فصل تقوم بتنزيله",
                color = MangaColors.Muted.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ─── Section Header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─── Download Task Card ───────────────────────────────────────────────────────

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskEntity,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onExpand: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when (task.status) {
        "running" -> MangaColors.Cyan
        "completed" -> MangaColors.Primary
        "failed", "cancelled" -> MangaColors.Error
        else -> MangaColors.Yellow
    }

    val progress by animateFloatAsState(
        targetValue = task.progress.coerceIn(0f, 1f),
        label = "progress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Row 1: Cover + Title/Status + Actions ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Manga cover placeholder
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MangaColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (task.status) {
                            "running" -> Icons.Filled.CloudDownload
                            "completed" -> Icons.Filled.CheckCircle
                            "failed" -> Icons.Filled.ErrorOutline
                            "cancelled" -> Icons.Filled.Cancel
                            else -> Icons.Filled.HourglassEmpty
                        },
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = statusColor.copy(alpha = 0.7f)
                    )
                }

                // Title + status + progress info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.mangaTitle ?: "مانجا",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MangaColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        task.chapterTitle ?: task.chapterUrl.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(statusColor)
                        )
                        Text(
                            when (task.status) {
                                "queued" -> "في الانتظار"
                                "running" -> "جاري التنزيل"
                                "completed" -> "مكتمل"
                                "failed" -> "فشل"
                                "cancelled" -> "ملغي"
                                else -> task.status
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MangaColors.Muted
                        )
                        if (task.status == "running" && task.totalPages > 0) {
                            Text(
                                "• ${task.downloadedPages}/${task.totalPages} صفحة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MangaColors.Muted
                            )
                        }
                    }
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (task.status == "running" && onPause != null) {
                        IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Pause, "إيقاف", modifier = Modifier.size(18.dp), tint = MangaColors.Yellow)
                        }
                    }
                    if (task.status == "queued" && onResume != null) {
                        IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.PlayArrow, "استئناف", modifier = Modifier.size(18.dp), tint = MangaColors.Cyan)
                        }
                    }
                    if (onRetry != null && task.status in listOf("failed", "cancelled")) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, "إعادة المحاولة", modifier = Modifier.size(18.dp), tint = MangaColors.Cyan)
                        }
                    }
                    if (onCancel != null && task.status in listOf("queued", "running")) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, "إلغاء", modifier = Modifier.size(18.dp), tint = MangaColors.Muted)
                        }
                    }
                }
            }

            // ── Progress bar (active tasks only) ──
            if (task.status in listOf("running", "queued")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MangaColors.Cyan,
                        trackColor = MangaColors.SurfaceContainer,
                    )
                    if (task.totalPages > 0) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MangaColors.Cyan,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            // ── Error message ──
            if (task.status == "failed" && !task.errorMessage.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MangaColors.Error.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        task.errorMessage,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaColors.Error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── Expandable chapters section ──
            val totalPages = remember(task.pagesJson) {
                try {
                    org.json.JSONArray(task.pagesJson).length()
                } catch (_: Exception) { 0 }
            }

            if (totalPages > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val expandedText = if (expanded) "طي" else "عرض الصفحات ($totalPages)"
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            expandedText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MangaColors.Cyan
                        )
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MangaColors.Cyan
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(totalPages) { index ->
                                val pageStatus = when {
                                    index < task.downloadedPages -> "✓"
                                    index == task.downloadedPages && task.status == "running" -> "↓"
                                    else -> ""
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "صفحة ${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MangaColors.OnSurfaceVariant
                                    )
                                    Text(
                                        when {
                                            pageStatus == "✓" -> "✓"
                                            pageStatus == "↓" -> "↓"
                                            index < task.downloadedPages -> "✓"
                                            else -> "—"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            index < task.downloadedPages -> MangaColors.Green
                                            index == task.downloadedPages && task.status == "running" -> MangaColors.Cyan
                                            else -> MangaColors.Muted.copy(alpha = 0.4f)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Created / updated timestamps ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatDate(task.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Muted.copy(alpha = 0.5f)
                )
                if (task.retries > 0) {
                    Text(
                        "إعادة المحاولة: ${task.retries}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaColors.Muted.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun formatDate(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    return "$day/$month ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}
