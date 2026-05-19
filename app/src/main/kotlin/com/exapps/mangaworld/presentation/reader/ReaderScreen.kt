package com.exapps.mangaworld.presentation.reader

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity

@Composable
fun ReaderScreen(
    source: MangaSource,
    mangaId: String,
    chapterUrl: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    LaunchedEffect(chapterUrl) { viewModel.loadChapter(chapterUrl, mangaId, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val solverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data?.getStringExtra(WebViewSolverActivity.RESULT_COOKIES).orEmpty()
            val domain  = result.data?.getStringExtra(WebViewSolverActivity.EXTRA_DOMAIN).orEmpty()
            if (cookies.isNotBlank() && domain.isNotBlank()) {
                com.exapps.mangaworld.core.data.CookieCache.put(domain, cookies)
                viewModel.onCloudflareSolved(domain, cookies)
            } else {
                viewModel.loadChapter(chapterUrl, mangaId, source)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .systemBarsPadding()
    ) {
        when {
            state.isLoading -> ReaderLoading()
            state.error != null -> {
                if (state.error!!.startsWith("CLOUDFLARE_REQUIRED|")) {
                    val parts = state.error!!.split("|")
                    val domain = parts.getOrNull(1).orEmpty()
                    val url = parts.getOrNull(2).orEmpty()
                    ReaderCloudflareError(
                        domain = domain,
                        onBack = onBack,
                        onSolve = {
                            solverLauncher.launch(
                                Intent(ctx, WebViewSolverActivity::class.java)
                                    .putExtra(WebViewSolverActivity.EXTRA_URL, url)
                                    .putExtra(WebViewSolverActivity.EXTRA_DOMAIN, domain)
                            )
                        }
                    )
                } else {
                    ReaderError(state.error!!, onBack)
                }
            }
            state.pages.isEmpty() -> ReaderError("لا توجد صفحات", onBack)
            else -> ReaderContent(
                state = state,
                onPageChanged = viewModel::onPageChanged,
                onTap = viewModel::toggleControls,
                onModeChange = viewModel::setReaderMode
            )
        }

        // Top bar
        AnimatedVisibility(
            visible = state.showControls,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                currentPage = state.currentPage + 1,
                totalPages = state.totalPages,
                onBack = onBack,
                onModeChange = viewModel::setReaderMode,
                currentMode = state.readerMode,
                onDownload = viewModel::downloadCurrentChapter,
                downloadInProgress = state.downloadInProgress,
                onCancelDownload = viewModel::cancelDownload,
                onRetryDownload = viewModel::retryCurrentChapterDownload,
                canRetry = state.downloadMessage?.startsWith("فشل") == true
            )
        }

        // Bottom bar
        AnimatedVisibility(
            visible = state.showControls && state.pages.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                onPageSelected = viewModel::onPageChanged
            )
        }

        state.downloadMessage?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .padding(bottom = 70.dp)
            )
        }
    }
}

// ─── Reader Content ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    state: ReaderUiState,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onModeChange: (ReaderMode) -> Unit
) {
    when (state.readerMode) {
        ReaderMode.VERTICAL_SCROLL, ReaderMode.WEBTOON ->
            WebtoonReader(pages = state.pages, onTap = onTap, onPageChanged = onPageChanged)
        ReaderMode.HORIZONTAL_RTL ->
            HorizontalReader(pages = state.pages, rtl = true,
                initialPage = state.currentPage, onPageChanged = onPageChanged, onTap = onTap)
        ReaderMode.HORIZONTAL_LTR ->
            HorizontalReader(pages = state.pages, rtl = false,
                initialPage = state.currentPage, onPageChanged = onPageChanged, onTap = onTap)
    }
}

// ─── Webtoon/Vertical Reader ──────────────────────────────────────────────────

@Composable
private fun WebtoonReader(
    pages: List<ChapterPage>,
    onTap: () -> Unit,
    onPageChanged: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    // Track current page
    LaunchedEffect(listState.firstVisibleItemIndex) {
        onPageChanged(listState.firstVisibleItemIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().clickable(indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onTap() }
    ) {
        items(pages, key = { it.index }) { page ->
            MangaPageImage(
                page = page,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─── Horizontal Pager Reader ──────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HorizontalReader(
    pages: List<ChapterPage>,
    rtl: Boolean,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit
) {
    val orderedPages = if (rtl) pages.reversed() else pages
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, maxOf(0, orderedPages.size - 1))) { orderedPages.size }

    LaunchedEffect(pagerState.currentPage) {
        val realIndex = if (rtl) orderedPages.size - 1 - pagerState.currentPage else pagerState.currentPage
        onPageChanged(realIndex)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
            .clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onTap() }
    ) { pageIndex ->
        MangaPageImage(
            page = orderedPages[pageIndex],
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ─── Single Page Image ────────────────────────────────────────────────────────

@Composable
private fun MangaPageImage(page: ChapterPage, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(page.url)
                .crossfade(200)
                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build(),
            imageLoader = ctx.imageLoader,
            contentDescription = "Page ${page.index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
            onLoading = { isLoading = true; isError = false },
            onSuccess = { isLoading = false; isError = false },
            onError = { isLoading = false; isError = true }
        )
        if (isLoading) {
            CircularProgressIndicator(
                color = MangaColors.Primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        }
        if (isError) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            ) {
                Icon(Icons.Filled.BrokenImage, null, tint = MangaColors.Muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("فشل تحميل الصفحة ${page.index + 1}",
                    style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
            }
        }
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun ReaderTopBar(
    currentPage: Int,
    totalPages: Int,
    onBack: () -> Unit,
    onModeChange: (ReaderMode) -> Unit,
    currentMode: ReaderMode,
    onDownload: () -> Unit,
    downloadInProgress: Boolean,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    canRetry: Boolean
) {
    var showModeMenu by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color.White)
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text("$currentPage / $totalPages",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDownload, enabled = !downloadInProgress) {
                    Icon(Icons.Filled.Download, "تنزيل", tint = Color.White)
                }
                if (downloadInProgress) {
                    IconButton(onClick = onCancelDownload) {
                        Icon(Icons.Filled.Close, "إلغاء التنزيل", tint = Color.White)
                    }
                } else if (canRetry) {
                    IconButton(onClick = onRetryDownload) {
                        Icon(Icons.Filled.Refresh, "إعادة المحاولة", tint = Color.White)
                    }
                }
                Box {
                IconButton(onClick = { showModeMenu = true }) {
                    Icon(Icons.Filled.MoreVert, "إعدادات", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showModeMenu,
                    onDismissRequest = { showModeMenu = false },
                    modifier = Modifier.background(MangaColors.SurfaceContainer)
                ) {
                    ReaderMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (mode == currentMode)
                                        Icon(Icons.Filled.Check, null, tint = MangaColors.Primary,
                                            modifier = Modifier.size(16.dp))
                                    else Spacer(Modifier.size(16.dp))
                                    Text(mode.label, color = MangaColors.OnSurface,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            },
                            onClick = { onModeChange(mode); showModeMenu = false }
                        )
                    }
                }
                }
            }
        }
    }
}

// ─── Bottom Bar ───────────────────────────────────────────────────────────────

@Composable
private fun ReaderBottomBar(currentPage: Int, totalPages: Int, onPageSelected: (Int) -> Unit) {
    if (totalPages == 0) return
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Slider(
            value = currentPage.toFloat(),
            onValueChange = { onPageSelected(it.toInt()) },
            valueRange = 0f..(totalPages - 1).toFloat(),
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = MangaColors.Primary,
                activeTrackColor = MangaColors.Primary,
                inactiveTrackColor = Color(0x55FFFFFF)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text("1", style = MaterialTheme.typography.labelSmall, color = Color(0x88FFFFFF))
                Text("$totalPages", style = MaterialTheme.typography.labelSmall, color = Color(0x88FFFFFF))
            }
        }
    }
}

// ─── Loading / Error States ───────────────────────────────────────────────────

@Composable
private fun ReaderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MangaColors.Primary, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("جاري تحميل الفصل...",
                style = MaterialTheme.typography.bodyMedium, color = MangaColors.OnSurfaceVariant)
        }
    }
}

@Composable
private fun ReaderError(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MangaColors.Muted, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("فشل تحميل الفصل", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onBack,
                border = androidx.compose.foundation.BorderStroke(1.dp, MangaColors.OutlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("رجوع")
            }
        }
    }
}

@Composable
private fun ReaderCloudflareError(domain: String, onBack: () -> Unit, onSolve: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Filled.Shield, null, tint = MangaColors.Muted, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("تحقق Cloudflare مطلوب", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("المصدر $domain يحتاج حل التحقق مرة واحدة.", style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSolve) { Text("فتح أداة التحقق") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("رجوع") }
        }
    }
}
