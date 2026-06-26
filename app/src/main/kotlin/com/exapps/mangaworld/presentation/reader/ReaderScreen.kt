package com.exapps.mangaworld.presentation.reader

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focus.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.transform.Transformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
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
    onOpenCommunity: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    LaunchedEffect(chapterUrl, mangaId, source) { viewModel.loadChapter(chapterUrl, mangaId, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val activity = ctx as? Activity
    var noteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var annotationsSheetOpen by remember { mutableStateOf(false) }
    var commentsSheetOpen by remember { mutableStateOf(false) }
    var settingsSheetOpen by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    var commentSpoiler by remember { mutableStateOf(false) }
    var showSavePageDialog by remember { mutableStateOf(false) }
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

    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .systemBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (state.volumeButtonPageTurn && event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            val prev = state.currentPage - 1
                            if (prev >= 0) viewModel.onPageChanged(prev)
                            true
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            val next = state.currentPage + 1
                            if (next < state.totalPages) viewModel.onPageChanged(next)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Request focus so volume key events are received
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        DisposableEffect(state.secureReaderEnabled, activity) {
            val window = activity?.window
            if (state.secureReaderEnabled) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (state.secureReaderEnabled) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        SideEffect {
            activity?.window?.attributes = activity.window.attributes.apply {
                screenBrightness = state.brightness.coerceIn(0.05f, 1f)
            }
        }

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
                    onTap = { x, y ->
                        viewModel.onReaderTap(x, y)
                        if (state.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleControls()
                    },
                    onLongPress = { showSavePageDialog = true },
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
                canRetry = state.downloadMessage?.startsWith("فشل") == true,
                brightness = state.brightness,
                onBrightnessChange = viewModel::setBrightness,
                imageFilter = state.imageFilter,
                onImageFilterChange = viewModel::setImageFilter,
                incognitoMode = state.incognitoMode,
                onIncognitoChange = viewModel::setIncognito,
                hasBookmark = state.currentPage in state.bookmarkedPages,
                onToggleBookmark = {
                    if (state.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.toggleBookmarkCurrentPage()
                },
                onEditNote = {
                    noteText = state.pageNotes[state.currentPage].orEmpty()
                    noteDialog = true
                },
                onBrowseAnnotations = { annotationsSheetOpen = true },
                onOpenComments = { commentsSheetOpen = true },
                onOpenSettings = { settingsSheetOpen = true },
                liveReaders = state.liveReaders,
                hasPreviousChapter = state.prevChapterUrl != null,
                hasNextChapter = state.nextChapterUrl != null,
                onPreviousChapter = viewModel::openPreviousChapter,
                onNextChapter = viewModel::openNextChapter
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

        if (state.showReactionOverlay && state.currentPageReactions.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                state.currentPageReactions.take(8).forEach { reaction ->
                    Text(
                        reaction.emoji,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = maxWidth * reaction.normalizedX - 12.dp, y = maxHeight * reaction.normalizedY - 20.dp)
                            .background(Color(0x88000000), RoundedCornerShape(14.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.showControls && (state.showLiveReadersOverlay || state.showReactionOverlay),
            modifier = Modifier.align(Alignment.CenterStart),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .background(Color(0x88000000), RoundedCornerShape(18.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.showLiveReadersOverlay) {
                    Text("${state.liveReaders} قارئ", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                if (state.showReactionOverlay) {
                    listOf("🔥", "😂", "😱", "❤️").forEach { emoji ->
                        TextButton(onClick = { viewModel.sendReaction(emoji) }) { Text(emoji) }
                    }
                }
            }
        }

        if (noteDialog) {
            AlertDialog(
                onDismissRequest = { noteDialog = false },
                title = { Text("ملاحظة الصفحة ${state.currentPage + 1}") },
                text = {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MangaColors.OnSurface,
                            unfocusedTextColor = MangaColors.OnSurface
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveCurrentPageNote(noteText)
                        noteDialog = false
                    }) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { noteDialog = false }) { Text("إلغاء") }
                }
            )
        }

        if (showSavePageDialog) {
            AlertDialog(
                onDismissRequest = { showSavePageDialog = false },
                title = { Text("حفظ الصفحة") },
                text = { Text("هل تريد حفظ الصفحة ${state.currentPage + 1} في المعرض؟") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveCurrentPage()
                        showSavePageDialog = false
                    }) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePageDialog = false }) { Text("إلغاء") }
                }
            )
        }

        if (annotationsSheetOpen) {
            ModalBottomSheet(onDismissRequest = { annotationsSheetOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("الإشارات والملاحظات", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val annotatedPages = state.pages.filter { page ->
                        page.index in state.bookmarkedPages || !state.pageNotes[page.index].isNullOrBlank()
                    }
                    if (annotatedPages.isEmpty()) {
                        Text("لا توجد إشارات أو ملاحظات في هذا الفصل.", color = MangaColors.Muted)
                    } else {
                        annotatedPages.forEach { page ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(
                                        text = "الصفحة ${page.index + 1}${if (page.index in state.bookmarkedPages) " • محفوظة" else ""}",
                                        color = MangaColors.OnSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    state.pageNotes[page.index]?.takeIf { it.isNotBlank() }?.let { note ->
                                        Spacer(Modifier.height(4.dp))
                                        Text(note, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (commentsSheetOpen) {
            ModalBottomSheet(onDismissRequest = { commentsSheetOpen = false }) {
                ReaderCommentsSheet(
                    comments = state.chapterComments,
                    collapseSpoilersByDefault = state.spoilerCollapseDefault,
                    commentText = commentText,
                    onCommentTextChange = { commentText = it },
                    spoiler = commentSpoiler,
                    onSpoilerChange = { commentSpoiler = it },
                    onSend = {
                        viewModel.postReaderComment(commentText, commentSpoiler)
                        commentText = ""
                        commentSpoiler = false
                    },
                    onOpenCommunity = {
                        commentsSheetOpen = false
                        onOpenCommunity()
                    }
                )
            }
        }

        if (settingsSheetOpen) {
            ModalBottomSheet(onDismissRequest = { settingsSheetOpen = false }) {
                ReaderSettingsSheet(
                    state = state,
                    onModeChange = viewModel::setReaderMode,
                    onFilterChange = viewModel::setImageFilter,
                    onBrightnessChange = viewModel::setBrightness,
                    onIncognitoChange = viewModel::setIncognito,
                    onAutoNextChange = viewModel::setAutoOpenNextChapter,
                    onLiveReadersChange = viewModel::setShowLiveReadersOverlay,
                    onReactionsChange = viewModel::setShowReactionOverlay,
                    onDualPageChange = viewModel::setDualPageLandscape,
                    onWebtoonStitchChange = viewModel::setWebtoonAutoStitch,
                    onKeepScreenOnChange = viewModel::setKeepScreenOn,
                    onHapticsChange = viewModel::setHaptics,
                    onSmartPrefetchChange = viewModel::setSmartPrefetch,
                    onPageSpacingChange = viewModel::setPageSpacing,
                    onVolumeButtonChange = viewModel::setVolumeButton,
                    onDoubleTapZoomChange = viewModel::setDoubleTapZoom,
                    onShowPageNumberChange = viewModel::setShowPageNumber,
                    onDownload = { viewModel.downloadCurrentChapter() },
                    onCancelDownload = { viewModel.cancelDownload() },
                    onRetryDownload = { viewModel.retryCurrentChapterDownload() },
                    onToggleBookmark = { viewModel.toggleBookmarkCurrentPage() },
                    onEditNote = {
                        noteText = state.pageNotes[state.currentPage].orEmpty()
                        settingsSheetOpen = false
                        noteDialog = true
                    },
                    onBrowseAnnotations = { settingsSheetOpen = false; annotationsSheetOpen = true },
                    onOpenComments = { settingsSheetOpen = false; commentsSheetOpen = true }
                )
            }
        }
    }

    LaunchedEffect(state.currentPage, state.totalPages, state.hapticsEnabled) {
        if (state.hapticsEnabled && state.totalPages > 0 && state.currentPage == state.totalPages - 1) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

// ─── Reader Content ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    state: ReaderUiState,
    onPageChanged: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {},
    onModeChange: (ReaderMode) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    when (state.readerMode) {
            ReaderMode.VERTICAL_SCROLL, ReaderMode.WEBTOON ->
                WebtoonReader(
                    pages = state.pages,
                    initialPage = state.currentPage,
                    imageFilter = state.imageFilter,
                    autoStitch = state.webtoonAutoStitch,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    onPageChanged = onPageChanged,
                    pageSpacing = state.pageSpacing,
                    currentPage = state.currentPage
                )
        ReaderMode.HORIZONTAL_RTL ->
            if (state.dualPageLandscape && isLandscape) {
                DualPageReader(
                    pages = state.pages,
                    rtl = true,
                    initialPage = state.currentPage,
                    imageFilter = state.imageFilter,
                    onPageChanged = onPageChanged,
                    onTap = onTap,
                    onLongPress = onLongPress
                )
            } else {
                HorizontalReader(pages = state.pages, rtl = true,
                    initialPage = state.currentPage, imageFilter = state.imageFilter, onPageChanged = onPageChanged, onTap = onTap, onLongPress = onLongPress)
            }
        ReaderMode.HORIZONTAL_LTR ->
            if (state.dualPageLandscape && isLandscape) {
                DualPageReader(
                    pages = state.pages,
                    rtl = false,
                    initialPage = state.currentPage,
                    imageFilter = state.imageFilter,
                    onPageChanged = onPageChanged,
                    onTap = onTap,
                    onLongPress = onLongPress
                )
            } else {
                HorizontalReader(pages = state.pages, rtl = false,
                    initialPage = state.currentPage, imageFilter = state.imageFilter, onPageChanged = onPageChanged, onTap = onTap, onLongPress = onLongPress)
            }
    }
}

// ─── Webtoon/Vertical Reader ──────────────────────────────────────────────────

@Composable
private fun WebtoonReader(
    pages: List<ChapterPage>,
    initialPage: Int = 0,
    imageFilter: ReaderImageFilter,
    autoStitch: Boolean,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {},
    onPageChanged: (Int) -> Unit,
    pageSpacing: Int = 0,
    currentPage: Int = 0
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(0, maxOf(0, pages.size - 1))
    )

    // Only advance reading progress — never save backward scrolling
    var highestPage by remember { mutableIntStateOf(initialPage) }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        val newPage = listState.firstVisibleItemIndex
        if (newPage > highestPage) {
            highestPage = newPage
            onPageChanged(newPage)
        }
    }

    // Allow slider / external code to scroll to a specific page
    LaunchedEffect(currentPage) {
        if (currentPage in pages.indices && listState.firstVisibleItemIndex != currentPage) {
            listState.animateScrollToItem(currentPage)
        }
    }

    val spacing = when {
        autoStitch -> 0.dp
        pageSpacing > 0 -> pageSpacing.dp
        else -> 6.dp
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { _ -> onLongPress() },
                onTap = { offset ->
                    val nx = if (size.width == 0) 0.5f else offset.x / size.width.toFloat()
                    val ny = if (size.height == 0) 0.5f else offset.y / size.height.toFloat()
                    onTap(nx, ny)
                }
            )
        }
    ) {
        items(pages, key = { it.index }) { page ->
            MangaPageImage(page = page, imageFilter = imageFilter, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualPageReader(
    pages: List<ChapterPage>,
    rtl: Boolean,
    initialPage: Int,
    imageFilter: ReaderImageFilter,
    onPageChanged: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {}
) {
    val orderedPages = if (rtl) pages.reversed() else pages
    val spreadPages = orderedPages.chunked(2)
    val initialSpread = (initialPage / 2).coerceIn(0, maxOf(0, spreadPages.size - 1))
    val pagerState = rememberPagerState(initialPage = initialSpread) { spreadPages.size }

    LaunchedEffect(pagerState.currentPage) {
        val logicalIndex = pagerState.currentPage * 2
        val realIndex = if (rtl) orderedPages.size - 1 - logicalIndex else logicalIndex
        onPageChanged(realIndex.coerceIn(0, maxOf(0, pages.size - 1)))
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { _ -> onLongPress() },
                onTap = { offset ->
                    val nx = if (size.width == 0) 0.5f else offset.x / size.width.toFloat()
                    val ny = if (size.height == 0) 0.5f else offset.y / size.height.toFloat()
                    onTap(nx, ny)
                }
            )
        }
    ) { spreadIndex ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            spreadPages[spreadIndex].forEach { page ->
                MangaPageImage(page = page, imageFilter = imageFilter, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            if (spreadPages[spreadIndex].size == 1) {
                Box(Modifier.weight(1f).fillMaxHeight())
            }
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
    imageFilter: ReaderImageFilter,
    onPageChanged: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {}
) {
    val orderedPages = if (rtl) pages.reversed() else pages
    val adjustedInitial = if (rtl && orderedPages.isNotEmpty()) {
        (orderedPages.size - 1 - initialPage).coerceIn(0, maxOf(0, orderedPages.size - 1))
    } else {
        initialPage.coerceIn(0, maxOf(0, orderedPages.size - 1))
    }
    val pagerState = rememberPagerState(initialPage = adjustedInitial) { orderedPages.size }

    LaunchedEffect(pagerState.currentPage) {
        val realIndex = if (rtl) orderedPages.size - 1 - pagerState.currentPage else pagerState.currentPage
        onPageChanged(realIndex)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { _ -> onLongPress() },
                    onTap = { offset ->
                        val nx = if (size.width == 0) 0.5f else offset.x / size.width.toFloat()
                        val ny = if (size.height == 0) 0.5f else offset.y / size.height.toFloat()
                        onTap(nx, ny)
                    }
                )
            }
    ) { pageIndex ->
        // Pinch-to-zoom wrapper for each page
        var pageScale by remember { mutableFloatStateOf(1f) }
        var pageOffsetX by remember { mutableFloatStateOf(0f) }
        var pageOffsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Toggle zoom: 1x ↔ 2x
                            pageScale = if (pageScale > 1f) 1f else 2f
                            if (pageScale == 1f) { pageOffsetX = 0f; pageOffsetY = 0f }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (pageScale * zoom).coerceIn(1f, 5f)
                        pageOffsetX = if (newScale > 1f) (pageOffsetX + pan.x).coerceIn(-size.width * (newScale - 1f), size.width * (newScale - 1f)) else 0f
                        pageOffsetY = if (newScale > 1f) (pageOffsetY + pan.y).coerceIn(-size.height * (newScale - 1f), size.height * (newScale - 1f)) else 0f
                        pageScale = newScale
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            MangaPageImage(
                page = orderedPages[pageIndex],
                imageFilter = imageFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = pageScale,
                        scaleY = pageScale,
                        translationX = pageOffsetX,
                        translationY = pageOffsetY
                    )
            )
        }
    }
}

// ─── Single Page Image ────────────────────────────────────────────────────────

@Composable
private fun MangaPageImage(page: ChapterPage, imageFilter: ReaderImageFilter, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    val transformations: List<Transformation> = remember(imageFilter) {
        if (imageFilter == ReaderImageFilter.SMART_CROP) listOf(SmartCropTransformation()) else emptyList()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(page.url)
                .crossfade(200)
                .allowHardware(false)
                .precision(Precision.INEXACT)
                .withFirebaseTrace("reader_page")
                .apply { if (transformations.isNotEmpty()) transformations(transformations) }
                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build(),
            imageLoader = ctx.imageLoader,
            contentDescription = "Page ${page.index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
            colorFilter = imageFilter.toColorFilter(),
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
    canRetry: Boolean,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    imageFilter: ReaderImageFilter,
    onImageFilterChange: (ReaderImageFilter) -> Unit,
    incognitoMode: Boolean,
    onIncognitoChange: (Boolean) -> Unit,
    hasBookmark: Boolean,
    onToggleBookmark: () -> Unit,
    onEditNote: () -> Unit,
    onBrowseAnnotations: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenSettings: () -> Unit,
    liveReaders: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$currentPage / $totalPages",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text("$liveReaders live", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
                    Icon(Icons.Filled.NavigateBefore, "الفصل السابق", tint = Color.White)
                }
                IconButton(onClick = onNextChapter, enabled = hasNextChapter) {
                    Icon(Icons.Filled.NavigateNext, "الفصل التالي", tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.MoreVert, "إعدادات", tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderUiState,
    onModeChange: (ReaderMode) -> Unit,
    onFilterChange: (ReaderImageFilter) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onIncognitoChange: (Boolean) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onLiveReadersChange: (Boolean) -> Unit,
    onReactionsChange: (Boolean) -> Unit,
    onDualPageChange: (Boolean) -> Unit,
    onWebtoonStitchChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onSmartPrefetchChange: (Boolean) -> Unit,
    onPageSpacingChange: (Int) -> Unit,
    onVolumeButtonChange: (Boolean) -> Unit,
    onDoubleTapZoomChange: (Boolean) -> Unit,
    onShowPageNumberChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onEditNote: () -> Unit,
    onBrowseAnnotations: () -> Unit,
    onOpenComments: () -> Unit
) {
    var expandedSection by remember { mutableStateOf<String?>("actions") }

    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("إعدادات القارئ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Quick Actions
        SectionHeader("الإجراءات السريعة", "actions", expandedSection, { expandedSection = it }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = if (state.currentPage in state.bookmarkedPages) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    label = "إشارة",
                    onClick = onToggleBookmark
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Download,
                    label = "تنزيل",
                    onClick = onDownload,
                    enabled = !state.downloadInProgress
                )
                if (state.downloadInProgress) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Close,
                        label = "إلغاء",
                        onClick = onCancelDownload,
                        tint = MangaColors.Error
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.EditNote,
                    label = "ملاحظة",
                    onClick = onEditNote
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.FormatListBulleted,
                    label = "الإشارات",
                    onClick = onBrowseAnnotations
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Forum,
                    label = "النقاش",
                    onClick = onOpenComments
                )
            }
        }

        // Reading Mode Section
        SectionHeader("وضع القراءة", "mode", expandedSection, { expandedSection = it }) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.readerMode == mode,
                        onClick = { onModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderMode.entries.size)
                    ) { Text(mode.label) }
                }
            }
        }

        // Image Filter Section
        SectionHeader("فلتر الصورة", "filter", expandedSection, { expandedSection = it }) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderImageFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = state.imageFilter == filter,
                        onClick = { onFilterChange(filter) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderImageFilter.entries.size)
                    ) { Text(filter.label) }
                }
            }
        }

        // Brightness Section
        SectionHeader("السطوع", "brightness", expandedSection, { expandedSection = it }) {
            Slider(value = state.brightness, onValueChange = onBrightnessChange, valueRange = 0.05f..1f)
            Text("${(state.brightness * 100).toInt()}%", color = MangaColors.Muted)
        }

        // Page Spacing
        SectionHeader("المسافة بين الصفحات", "spacing", expandedSection, { expandedSection = it }) {
            Slider(
                value = state.pageSpacing.toFloat(),
                onValueChange = { onPageSpacingChange(it.toInt()) },
                valueRange = 0f..30f,
                steps = 6
            )
            Text("${state.pageSpacing}dp", color = MangaColors.Muted)
        }

        // Reading Options
        SectionHeader("خيارات القراءة", "reading", expandedSection, { expandedSection = it }) {
            SwitchRow("وضع خفي", state.incognitoMode, onIncognitoChange)
            SwitchRow("الانتقال التلقائي للفصل التالي", state.autoOpenNextChapter, onAutoNextChange)
            SwitchRow("إبقاء الشاشة مضاءة", state.keepScreenOn, onKeepScreenOnChange)
            SwitchRow("إظهار رقم الصفحة", state.showPageNumber, onShowPageNumberChange)
            SwitchRow("التحميل المسبق الذكي", state.smartPrefetchEnabled, onSmartPrefetchChange)
        }

        // Overlays
        SectionHeader("الطبقات العلوية", "overlays", expandedSection, { expandedSection = it }) {
            SwitchRow("إظهار عداد القراء", state.showLiveReadersOverlay, onLiveReadersChange)
            SwitchRow("إظهار التفاعلات", state.showReactionOverlay, onReactionsChange)
        }

        // Display
        SectionHeader("العرض", "display", expandedSection, { expandedSection = it }) {
            SwitchRow("وضع الصفحتين أفقياً", state.dualPageLandscape, onDualPageChange)
            SwitchRow("دمج صفحات الويب تون", state.webtoonAutoStitch, onWebtoonStitchChange)
        }

        // Gestures
        SectionHeader("الإجراءات", "gestures", expandedSection, { expandedSection = it }) {
            SwitchRow("زر الصوت للتنقل بين الصفحات", state.volumeButtonPageTurn, onVolumeButtonChange)
            SwitchRow("التكبير بالنقر المزدوج", state.doubleTapZoom, onDoubleTapZoomChange)
            SwitchRow("الاهتزازات اللمسية", state.hapticsEnabled, onHapticsChange)
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MangaColors.OnSurface
) {
    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = tint
        )
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    sectionKey: String,
    expandedSection: String?,
    onToggle: (String) -> Unit,
    content: @Composable () -> Unit
) {
    val isExpanded = expandedSection == sectionKey

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(if (isExpanded) "" else sectionKey) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MangaColors.Muted
                )
            }
            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MangaColors.OnSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReaderCommentsSheet(
    comments: List<CommunityComment>,
    collapseSpoilersByDefault: Boolean,
    commentText: String,
    onCommentTextChange: (String) -> Unit,
    spoiler: Boolean,
    onSpoilerChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onOpenCommunity: () -> Unit
) {
    val expandedSpoilers = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("تعليقات الفصل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenCommunity) { Text("فتح المجتمع") }
        }
        if (comments.isEmpty()) {
            Text("لا توجد تعليقات بعد.", color = MangaColors.Muted)
        } else {
            comments.takeLast(20).forEach { comment ->
                Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(comment.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                        if (comment.spoiler && collapseSpoilersByDefault && comment.id !in expandedSpoilers) {
                            TextButton(onClick = { expandedSpoilers.add(comment.id) }) { Text("إظهار السبويْلر") }
                        } else {
                            Text(comment.text, color = MangaColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        }
        OutlinedTextField(value = commentText, onValueChange = onCommentTextChange, modifier = Modifier.fillMaxWidth(), label = { Text("أضف تعليقاً") })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = spoiler, onCheckedChange = onSpoilerChange)
                Text("سبويْلر")
            }
            Button(onClick = onSend, enabled = commentText.isNotBlank()) { Text("إرسال") }
        }
    }
}

private fun ReaderImageFilter.toColorFilter(): ColorFilter? {
    val matrix = when (this) {
        ReaderImageFilter.NONE, ReaderImageFilter.SMART_CROP -> return null
        ReaderImageFilter.GRAYSCALE -> ColorMatrix().apply { setToSaturation(0f) }
        ReaderImageFilter.SEPIA -> ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        ReaderImageFilter.HIGH_CONTRAST -> ColorMatrix(floatArrayOf(
            1.4f, 0f, 0f, 0f, -20f,
            0f, 1.4f, 0f, 0f, -20f,
            0f, 0f, 1.4f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f
        ))
        ReaderImageFilter.WARM_TINT -> ColorMatrix(floatArrayOf(
            1.1f, 0.1f, 0f, 0f, 10f,
            0f, 1.0f, 0f, 0f, 5f,
            0f, 0f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        ReaderImageFilter.COOL_TINT -> ColorMatrix(floatArrayOf(
            0.9f, 0f, 0.1f, 0f, 0f,
            0f, 1.0f, 0.1f, 0f, 0f,
            0f, 0.1f, 1.1f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        ))
        ReaderImageFilter.OLED_BLACK -> ColorMatrix(floatArrayOf(
            1.2f, 0f, 0f, 0f, -30f,
            0f, 1.2f, 0f, 0f, -30f,
            0f, 0f, 1.2f, 0f, -30f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
    return ColorFilter.colorMatrix(matrix)
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
                Text("${currentPage + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White)
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
