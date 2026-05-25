package com.exapps.mangaworld.presentation.detail

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.rememberDominantColor
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun MangaDetailScreen(
    source: MangaSource,
    slug: String,
    onChapterClick: (chapterUrl: String, mangaId: String) -> Unit,
    onOpenCommunity: (mangaId: String) -> Unit,
    onOpenChapterCommunity: (mangaId: String, chapterUrl: String) -> Unit,
    onOpenOtherSource: (sourceId: String, slug: String) -> Unit,
    onBack: () -> Unit,
    viewModel: MangaDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(slug, source) { viewModel.load(slug, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Launcher for Cloudflare WebView solver
    val cfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data?.getStringExtra(WebViewSolverActivity.RESULT_COOKIES).orEmpty()
            val domain  = result.data?.getStringExtra(WebViewSolverActivity.EXTRA_DOMAIN).orEmpty()
            if (cookies.isNotBlank() && domain.isNotBlank()) {
                viewModel.onCloudflareSolved(domain, cookies)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MangaColors.Background)) {
        when {
            state.isLoading && state.manga == null -> DetailShimmer()
            state.cloudflareUrl != null && state.manga == null -> CloudflareRequired(
                onSolve = {
                    cfLauncher.launch(
                        android.content.Intent(ctx, WebViewSolverActivity::class.java).apply {
                            putExtra(WebViewSolverActivity.EXTRA_URL, state.cloudflareUrl)
                            putExtra(WebViewSolverActivity.EXTRA_DOMAIN, state.cloudfareDomain ?: "")
                        }
                    )
                }
            )
            state.error != null && state.manga == null -> DetailError(
                state.error!!, onRetry = { viewModel.load(slug, source) }
            )
            state.manga != null -> DetailContent(
                manga = state.manga!!,
                isFavorite = state.isFavorite,
                otherSourceMatches = state.otherSourceMatches,
                readChapters = state.readChapters,
                chaptersReversed = state.chaptersReversed,
                sortedChapters = viewModel.sortedChapters(),
                downloadingChapters = state.downloadingChapters,
                onToggleFav = viewModel::toggleFavorite,
                onToggleOrder = viewModel::toggleChaptersOrder,
                onDownloadChapter = viewModel::downloadChapter,
                onShowDownloadDialog = viewModel::showDownloadDialog,
                onOpenCommunity = { onOpenCommunity("${source.id}_$slug") },
                onOpenChapterCommunity = onOpenChapterCommunity,
                onOpenOtherSource = onOpenOtherSource,
                onShowAddToList = viewModel::showAddToListDialog,
                onChapterClick = { ch -> onChapterClick(ch.url, "${source.id}_$slug") }
            )
        }

        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = 12.dp, start = 8.dp)
                .align(Alignment.TopStart)
                .background(Color(0x99000000), CircleShape)
        ) {
            Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color.White)
        }
    }

    // Download options dialog
    if (state.showDownloadDialog) {
        DownloadOptionsDialog(
            unreadCount = viewModel.sortedChapters().count { !it.isRead && !it.isDownloaded },
            allCount = viewModel.sortedChapters().count { !it.isDownloaded },
            onDownloadAll = viewModel::downloadAllChapters,
            onDownloadUnread = viewModel::downloadUnreadChapters,
            onDismiss = viewModel::hideDownloadDialog
        )
    }

    if (state.showAddToListDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddToListDialog,
            title = { Text("إضافة إلى قائمة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.userLists.isEmpty()) {
                        Text("أنشئ قائمة مخصصة أولاً من صفحة الملف الشخصي.")
                    } else {
                        state.userLists.forEach { list ->
                            OutlinedButton(onClick = { viewModel.addCurrentMangaToList(list.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text(list.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::hideAddToListDialog) { Text("إغلاق") }
            },
            dismissButton = {}
        )
    }
}

@Composable
private fun DetailContent(
    manga: MangaDetail,
    isFavorite: Boolean,
    otherSourceMatches: List<MangaItem>,
    readChapters: Set<Float>,
    chaptersReversed: Boolean,
    sortedChapters: List<Chapter>,
    downloadingChapters: Set<Float>,
    onToggleFav: () -> Unit,
    onToggleOrder: () -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onShowDownloadDialog: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenChapterCommunity: (mangaId: String, chapterUrl: String) -> Unit,
    onOpenOtherSource: (sourceId: String, slug: String) -> Unit,
    onShowAddToList: () -> Unit,
    onChapterClick: (Chapter) -> Unit
) {
    val ctx = LocalContext.current
    var descExpanded by remember { mutableStateOf(false) }
    val dominantColor = rememberDominantColor(manga.coverUrl)

    LazyColumn(Modifier.fillMaxSize()) {
        // ── Header ──────────────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(manga.coverUrl).crossfade(true).withFirebaseTrace("detail_cover").build(),
                    imageLoader = ctx.imageLoader,
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(20.dp)
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf((dominantColor ?: Color(0x88000000)).copy(alpha = 0.72f), MangaColors.Background),
                            startY = 0f, endY = Float.POSITIVE_INFINITY
                        )
                    )
                )
                Row(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(8.dp),
                        modifier = Modifier.size(110.dp, 155.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(manga.coverUrl).crossfade(true).withFirebaseTrace("detail_related_cover").build(),
                            imageLoader = ctx.imageLoader,
                            contentDescription = manga.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                        Text(
                            manga.title, style = MaterialTheme.typography.titleLarge,
                            color = Color.White, fontWeight = FontWeight.Bold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TypeBadge(manga.type); StatusBadge(manga.status)
                        }
                        Spacer(Modifier.height(6.dp))
                        SourceBadge(manga.source)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatItem(Icons.Filled.MenuBook, "${manga.totalChapters}", "فصل")
                            if (manga.views != null)
                                StatItem(Icons.Filled.Visibility, manga.views, "مشاهدة")
                        }
                    }
                }
            }
        }

        // ── Action buttons ───────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (sortedChapters.isNotEmpty()) {
                    val firstUnread = sortedChapters.lastOrNull { !readChapters.contains(it.number) }
                        ?: sortedChapters.last()
                    GradientButton(
                        text = "ابدأ القراءة",
                        onClick = { onChapterClick(firstUnread) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Download All FAB
                IconButton(
                    onClick = onShowDownloadDialog,
                    modifier = Modifier
                        .size(50.dp)
                        .background(MangaColors.SurfaceContainer, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Filled.Download, "تنزيل", tint = MangaColors.Cyan)
                }
                // Favourite
                IconButton(
                    onClick = onToggleFav,
                    modifier = Modifier
                        .size(50.dp)
                        .background(
                            if (isFavorite) MangaColors.Primary else MangaColors.SurfaceContainer,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        if (isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        null,
                        tint = if (isFavorite) Color.White else MangaColors.PrimaryLight
                    )
                }
                IconButton(
                    onClick = onOpenCommunity,
                    modifier = Modifier
                        .size(50.dp)
                        .background(MangaColors.SurfaceContainer, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Filled.Forum, "المجتمع", tint = MangaColors.Cyan)
                }
                IconButton(
                    onClick = onShowAddToList,
                    modifier = Modifier
                        .size(50.dp)
                        .background(MangaColors.SurfaceContainer, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Filled.PlaylistAdd, "إضافة لقائمة", tint = MangaColors.Cyan)
                }
            }
        }

        // ── Genres ──────────────────────────────────────────────────────────
        if (manga.genres.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(manga.genres) { g -> GenreChip(label = g, selected = false, onClick = {}) }
                }
            }
        }

        // ── Description ──────────────────────────────────────────────────────
        if (manga.description.isNotEmpty()) {
            item {
                GradientDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "القصة", style = MaterialTheme.typography.titleSmall,
                        color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        manga.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MangaColors.OnSurfaceVariant,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (manga.description.length > 150) {
                        TextButton(onClick = { descExpanded = !descExpanded }) {
                            Text(
                                if (descExpanded) "عرض أقل" else "عرض المزيد",
                                color = MangaColors.Cyan,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (manga.authorName != null || manga.artistName != null || manga.alternativeTitles.isNotEmpty()) {
            item {
                GradientDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("معلومات إضافية", style = MaterialTheme.typography.titleSmall, color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold)
                    manga.authorName?.let { InfoRow("المؤلف", it) }
                    manga.artistName?.let { InfoRow("الرسام", it) }
                    if (manga.alternativeTitles.isNotEmpty()) InfoRow("أسماء بديلة", manga.alternativeTitles.joinToString(" • "))
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (otherSourceMatches.isNotEmpty()) {
            item {
                GradientDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اقرأ من مصادر أخرى", style = MaterialTheme.typography.titleSmall, color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold)
                    otherSourceMatches.forEach { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.source.displayName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                                    Text(item.title, color = MangaColors.OnSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(onClick = { onOpenOtherSource(item.source.id, item.slug) }) {
                                    Text("فتح")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (manga.relatedManga.isNotEmpty()) {
            item {
                GradientDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أعمال مشابهة", style = MaterialTheme.typography.titleSmall, color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold)
                    manga.relatedManga.take(8).forEach { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                                    Text(item.source.displayName, color = MangaColors.OnSurfaceVariant)
                                }
                                OutlinedButton(onClick = { onOpenOtherSource(item.source.id, item.slug) }) {
                                    Text("فتح")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // ── Chapter list header ───────────────────────────────────────────────
        item {
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "الفصول (${sortedChapters.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurface, fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Download all button in header
                    if (sortedChapters.any { !it.isDownloaded }) {
                        TextButton(onClick = onShowDownloadDialog) {
                            Icon(
                                Icons.Filled.DownloadForOffline,
                                null, modifier = Modifier.size(16.dp),
                                tint = MangaColors.Cyan
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "تنزيل",
                                color = MangaColors.Cyan,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    IconButton(onClick = onToggleOrder, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (chaptersReversed) Icons.Filled.KeyboardArrowDown
                            else Icons.Filled.KeyboardArrowUp,
                            "ترتيب", tint = MangaColors.Cyan
                        )
                    }
                }
            }
        }

        // ── Chapters ─────────────────────────────────────────────────────────
        items(sortedChapters, key = { it.url.ifBlank { it.id } }) { chapter ->
            ChapterItem(
                chapter = chapter,
                isRead = readChapters.contains(chapter.number),
                isDownloading = downloadingChapters.contains(chapter.number),
                onClick = { onChapterClick(chapter) },
                onDownload = { onDownloadChapter(chapter) },
                onOpenChapterComments = { onOpenChapterCommunity("${manga.source.id}_${manga.slug}", chapter.url) }
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun ChapterItem(
    chapter: Chapter,
    isRead: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onOpenChapterComments: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isRead) Color(0x0AFFFFFF) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (chapter.coverUrl.isNotBlank()) {
                MangaCover(
                    url = chapter.coverUrl,
                    contentDescription = chapter.title ?: "Chapter cover",
                    modifier = Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    if (isRead) MangaColors.Muted else MangaColors.Primary
                )
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "الفصل ${chapter.displayNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRead) MangaColors.Muted else MangaColors.OnSurface,
                    fontWeight = FontWeight.Medium
                )
                if (!chapter.title.isNullOrEmpty()) {
                    Text(
                        chapter.title, style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chapter.dateText != null) {
                    Text(chapter.dateText, style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
                }
                if (chapter.isPaid) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0x22FFD700), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(10.dp), tint = MangaColors.Yellow)
                        Spacer(Modifier.width(3.dp))
                        Text("مدفوع", style = MaterialTheme.typography.labelSmall, color = MangaColors.Yellow)
                    }
                }
            }

            // Per-chapter download button
            when {
                isDownloading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MangaColors.Cyan
                )
                chapter.isDownloaded -> Icon(
                    Icons.Filled.DownloadDone, null,
                    modifier = Modifier.size(20.dp), tint = MangaColors.Primary
                )
                else -> IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Download, "تنزيل الفصل",
                        modifier = Modifier.size(18.dp), tint = MangaColors.Muted
                    )
                }
            }
            IconButton(onClick = onOpenChapterComments, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Forum, "تعليقات الفصل", modifier = Modifier.size(18.dp), tint = MangaColors.Muted)
            }
        }
    }
    GradientDivider(Modifier.padding(horizontal = 16.dp))
}

// ─── Download options dialog ──────────────────────────────────────────────────

@Composable
private fun DownloadOptionsDialog(
    unreadCount: Int,
    allCount: Int,
    onDownloadAll: () -> Unit,
    onDownloadUnread: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = {
            Text("خيارات التنزيل", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "اختر الفصول التي تريد تنزيلها للقراءة بدون إنترنت.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MangaColors.OnSurfaceVariant
                )
                if (allCount == 0) {
                    Text(
                        "جميع الفصول محملة بالفعل ✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.Primary
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allCount > 0) {
                    Button(
                        onClick = onDownloadAll,
                        colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تنزيل جميع الفصول ($allCount)")
                    }
                }
                if (unreadCount > 0) {
                    OutlinedButton(
                        onClick = onDownloadUnread,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MangaColors.Cyan)
                    ) {
                        Icon(Icons.Filled.BookmarkAdd, null, modifier = Modifier.size(16.dp), tint = MangaColors.Cyan)
                        Spacer(Modifier.width(6.dp))
                        Text("تنزيل غير المقروءة ($unreadCount)", color = MangaColors.Cyan)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("إلغاء", color = MangaColors.Muted)
                }
            }
        },
        dismissButton = {}
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MangaColors.PrimaryLight, modifier = Modifier.size(14.dp))
        Text("$value $label", style = MaterialTheme.typography.labelSmall, color = MangaColors.OnSurfaceVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DetailShimmer() {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ShimmerBox(Modifier.fillMaxWidth().height(320.dp)) }
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShimmerBox(Modifier.weight(1f).height(50.dp), RoundedCornerShape(12.dp))
                ShimmerBox(Modifier.size(50.dp), RoundedCornerShape(12.dp))
                ShimmerBox(Modifier.size(50.dp), RoundedCornerShape(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        items(8) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ShimmerBox(Modifier.size(8.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
                Column {
                    ShimmerBox(Modifier.width(120.dp).height(14.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(4.dp))
                    ShimmerBox(Modifier.width(80.dp).height(11.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
private fun CloudflareRequired(onSolve: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Shield,
        title = "تحقق Cloudflare مطلوب",
        subtitle = "يحتاج هذا المصدر إلى التحقق من المتصفح. اضغط لحل التحدي ثم ستُفتح المانجا تلقائياً.",
        action = {
            GradientButton(
                text = "حل تحدي Cloudflare",
                onClick = onSolve,
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 32.dp)
            )
        },
        modifier = androidx.compose.ui.Modifier.fillMaxSize()
    )
}

@Composable
private fun DetailError(message: String, onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.ErrorOutline,
        title = "حدث خطأ",
        subtitle = message,
        action = { GradientButton("إعادة المحاولة", onRetry) },
        modifier = Modifier.fillMaxSize()
    )
}
