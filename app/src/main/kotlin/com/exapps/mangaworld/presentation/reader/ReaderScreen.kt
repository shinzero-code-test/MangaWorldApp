package com.exapps.mangaworld.presentation.reader
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity
import android.view.accessibility.AccessibilityManager

private fun android.content.Context.announceForAccessibility(text: String) {
    val am = getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am?.isEnabled == true) {
        val event = android.view.accessibility.AccessibilityEvent.obtain().apply {
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT
            this.text.add(text)
        }
        am.sendAccessibilityEvent(event)
    }
}

@Composable
fun ReaderScreen(
    source: MangaSource,
    mangaId: String,
    chapterUrl: String,
    onBack: () -> Unit,
    communityEnabled: Boolean,
    isSignedIn: Boolean = true,
    onOpenCommunity: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    LaunchedEffect(chapterUrl, mangaId, source) { viewModel.loadChapter(chapterUrl, mangaId, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val activity = ctx as? Activity

    // Announce page changes to TalkBack users
    LaunchedEffect(state.currentPage) {
        if (state.currentPage >= 0 && state.totalPages > 0) {
            ctx.announceForAccessibility(ctx.getString(R.string.reader_page_counter, "${state.currentPage + 1}", "${state.totalPages}"))
        }
    }
    var noteDialog by rememberSaveable { mutableStateOf(false) }
    var noteText by rememberSaveable { mutableStateOf("") }
    var annotationsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var commentsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var commentText by rememberSaveable { mutableStateOf("") }
    var commentSpoiler by rememberSaveable { mutableStateOf(false) }
    var showSavePageDialog by rememberSaveable { mutableStateOf(false) }
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

    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .systemBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (state.volumeButtonPageTurn && event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            val prev = (state.currentPage - 1).coerceAtLeast(0)
                            if (prev != state.currentPage) viewModel.onPageChanged(prev)
                            // Consume so the system volume UI doesn't appear while paging.
                            true
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            val next = (state.currentPage + 1).coerceAtMost(maxOf(0, state.totalPages - 1))
                            if (next != state.currentPage) viewModel.onPageChanged(next)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Volume keys only reach onPreviewKeyEvent when something is focused.
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
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

        // Keep-screen-on must be applied AND cleared with the reader, or the flag
        // leaks to the rest of the app.
        DisposableEffect(state.keepScreenOn, activity) {
            val window = activity?.window
            if (state.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        // Brightness override: capture the previous value once and restore it on
        // dispose so leaving the reader never leaves the app dimmed.
        val previousBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness }
        DisposableEffect(state.brightness, activity) {
            val act = activity
            if (act != null) {
                val attributes = act.window.attributes
                attributes.screenBrightness = state.brightness.coerceIn(0.05f, 1f)
                act.window.attributes = attributes
            }
            onDispose {
                val owner = activity ?: return@onDispose
                val attrs = owner.window.attributes
                attrs.screenBrightness = previousBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                owner.window.attributes = attrs
            }
        }

        when {
            state.isLoading -> ReaderLoading()
            state.error != null -> {
                // Announce error to TalkBack users
                LaunchedEffect(state.error) {
                    ctx.announceForAccessibility(ctx.getString(R.string.error_generic))
                }
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
            state.pages.isEmpty() -> ReaderError(stringResource(R.string.no_pages), onBack)
                else -> ReaderContent(
                    state = state,
                    onPageChanged = viewModel::onPageChanged,
                    onTap = { x, y ->
                        viewModel.onReaderTap(x, y)
                        if (state.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Horizontal modes: side taps turn pages (respect RTL),
                        // centre toggles UI. Vertical/webtoon: tap toggles UI so
                        // scrolling never triggers accidental page jumps.
                        when (state.readerMode) {
                            ReaderMode.HORIZONTAL_RTL -> {
                                when {
                                    x < 0.33f -> {
                                        val next = (state.currentPage + 1).coerceAtMost(maxOf(0, state.totalPages - 1))
                                        if (next != state.currentPage) viewModel.onPageChanged(next) else viewModel.toggleControls()
                                    }
                                    x > 0.67f -> {
                                        val prev = (state.currentPage - 1).coerceAtLeast(0)
                                        if (prev != state.currentPage) viewModel.onPageChanged(prev) else viewModel.toggleControls()
                                    }
                                    else -> viewModel.toggleControls()
                                }
                            }
                            ReaderMode.HORIZONTAL_LTR -> {
                                when {
                                    x < 0.33f -> {
                                        val prev = (state.currentPage - 1).coerceAtLeast(0)
                                        if (prev != state.currentPage) viewModel.onPageChanged(prev) else viewModel.toggleControls()
                                    }
                                    x > 0.67f -> {
                                        val next = (state.currentPage + 1).coerceAtMost(maxOf(0, state.totalPages - 1))
                                        if (next != state.currentPage) viewModel.onPageChanged(next) else viewModel.toggleControls()
                                    }
                                    else -> viewModel.toggleControls()
                                }
                            }
                            else -> viewModel.toggleControls()
                        }
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
                onRetryDownload = viewModel::retryCurrentChapterDownload,
                // Typed failure signal — display strings must never drive behavior.
                canRetry = state.lastDownloadFailed,
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
                showPageNumber = state.showPageNumber,
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
                    Text(stringResource(R.string.fmt_041, state.liveReaders), color = Color.White, style = MaterialTheme.typography.labelMedium)
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
                title = { Text(stringResource(R.string.fmt_079, state.currentPage + 1)) },
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
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { noteDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showSavePageDialog) {
            AlertDialog(
                onDismissRequest = { showSavePageDialog = false },
                title = { Text(stringResource(R.string.reader_save_page)) },
                text = { Text(stringResource(R.string.fmt_080, state.currentPage + 1)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveCurrentPage()
                        showSavePageDialog = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePageDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (annotationsSheetOpen) {
            GlassBottomSheet(onDismissRequest = { annotationsSheetOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.bookmarks_and_notes), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val annotatedPages = state.pages.filter { page ->
                        page.index in state.bookmarkedPages || !state.pageNotes[page.index].isNullOrBlank()
                    }
                    if (annotatedPages.isEmpty()) {
                        Text(stringResource(R.string.str_358), color = MangaColors.Muted)
                    } else {
                        annotatedPages.forEach { page ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(
                                        // Own formatted resource instead of concatenating a literal suffix.
                                        text = if (page.index in state.bookmarkedPages)
                                            stringResource(R.string.reader_page_bookmarked, page.index + 1)
                                        else stringResource(R.string.fmt_052, page.index + 1),
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

        if (communityEnabled && commentsSheetOpen) {
            GlassBottomSheet(onDismissRequest = { commentsSheetOpen = false }) {
                ReaderCommentsSheet(
                    comments = state.chapterComments,
                    collapseSpoilersByDefault = state.spoilerCollapseDefault,
                    // Guests read-only: composer hidden, sign-in hint shown instead (H6).
                    isSignedIn = isSignedIn,
                    commentText = commentText,
                    onCommentTextChange = { commentText = it },
                    spoiler = commentSpoiler,
                    onSpoilerChange = { commentSpoiler = it },
                    errorMessage = state.chapterCommentError,
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
            GlassBottomSheet(onDismissRequest = { settingsSheetOpen = false }) {
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
                    onToggleBookmark = { viewModel.toggleBookmarkCurrentPage() },
                    onEditNote = {
                        noteText = state.pageNotes[state.currentPage].orEmpty()
                        settingsSheetOpen = false
                        noteDialog = true
                    },
                    onBrowseAnnotations = { settingsSheetOpen = false; annotationsSheetOpen = true },
                    communityEnabled = communityEnabled,
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
                    currentPage = state.currentPage,
                    chapterRanges = state.chapterRanges,
                    doubleTapZoomEnabled = state.doubleTapZoom
                )
        ReaderMode.HORIZONTAL_RTL ->
            if (state.dualPageLandscape && isLandscape) {
                DualPageReader(
                    pages = state.pages,
                    rtl = true,
                    initialPage = state.currentPage,
                    currentPage = state.currentPage,
                    imageFilter = state.imageFilter,
                    onPageChanged = onPageChanged,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    pageSpacing = state.pageSpacing,
                    doubleTapZoomEnabled = state.doubleTapZoom
                )
            } else {
                HorizontalReader(pages = state.pages, rtl = true,
                    initialPage = state.currentPage, currentPage = state.currentPage,
                    imageFilter = state.imageFilter, onPageChanged = onPageChanged, onTap = onTap, onLongPress = onLongPress,
                    doubleTapZoomEnabled = state.doubleTapZoom)
            }
        ReaderMode.HORIZONTAL_LTR ->
            if (state.dualPageLandscape && isLandscape) {
                DualPageReader(
                    pages = state.pages,
                    rtl = false,
                    initialPage = state.currentPage,
                    currentPage = state.currentPage,
                    imageFilter = state.imageFilter,
                    onPageChanged = onPageChanged,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    pageSpacing = state.pageSpacing,
                    doubleTapZoomEnabled = state.doubleTapZoom
                )
            } else {
                HorizontalReader(pages = state.pages, rtl = false,
                    initialPage = state.currentPage, currentPage = state.currentPage,
                    imageFilter = state.imageFilter, onPageChanged = onPageChanged, onTap = onTap, onLongPress = onLongPress,
                    doubleTapZoomEnabled = state.doubleTapZoom)
            }
    }
}

// ─── Shared zoomable page (all modes) ─────────────────────────────────────────
// Single source of truth for double-tap + pinch. Uses `transformable` with
// canPan gated on zoom so un-zoomed drags bubble to the parent pager/list
// (horizontal swipe was previously swallowed by an always-on
// detectTransformGestures, breaking page turns).

@Composable
private fun ZoomableMangaPage(
    page: ChapterPage,
    imageFilter: ReaderImageFilter,
    doubleTapZoomEnabled: Boolean,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember(page.url) { mutableFloatStateOf(1f) }
    var offset by remember(page.url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformState = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (newScale > 1f) {
            val maxX = 1200f * (newScale - 1f)
            val maxY = 2000f * (newScale - 1f)
            androidx.compose.ui.geometry.Offset(
                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                (offset.y + panChange.y).coerceIn(-maxY, maxY)
            )
        } else androidx.compose.ui.geometry.Offset.Zero
        scale = newScale
    }
    Box(
        modifier = modifier
            .transformable(
                state = transformState,
                canPan = { scale > 1f },
                lockRotationOnZoomPan = true
            )
            .pointerInput(doubleTapZoomEnabled) {
                detectTapGestures(
                    onLongPress = { _ -> onLongPress() },
                    onTap = { tapOffset ->
                        val nx = if (size.width == 0) 0.5f else tapOffset.x / size.width.toFloat()
                        val ny = if (size.height == 0) 0.5f else tapOffset.y / size.height.toFloat()
                        onTap(nx, ny)
                    },
                    onDoubleTap = {
                        if (doubleTapZoomEnabled) {
                            if (scale > 1f) {
                                scale = 1f
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                scale = 2f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        MangaPageImage(
            page = page,
            imageFilter = imageFilter,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
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
    currentPage: Int = 0,
    chapterRanges: List<ReaderChapterRange> = emptyList(),
    doubleTapZoomEnabled: Boolean = true
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(0, maxOf(0, pages.size - 1))
    )

    // Track the effective visible page. firstVisibleItemIndex alone never
    // reaches a short last page (it stays on the previous item while the last
    // page is fully visible below). Promote to the last visible index when the
    // combined list end is on screen so progress + continuous append fire.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = listState.firstVisibleItemIndex
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: first
            Triple(first, last, listState.isScrollInProgress)
        }
            .filter { (_, _, scrolling) -> !scrolling }
            .map { (first, last, _) ->
                if (pages.isNotEmpty() && last >= pages.size - 1) pages.size - 1 else first
            }
            .distinctUntilChanged()
            .collect { page -> if (page in pages.indices) onPageChanged(page) }
    }

    // Allow slider / volume / tap-zone code to scroll to a specific page.
    // Guard against feedback: only animate when the target differs from BOTH
    // first and last visible (continuous lists show 2+ items at once).
    LaunchedEffect(currentPage, pages.size) {
        if (currentPage in pages.indices) {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
            if (currentPage != first && currentPage != last) {
                listState.animateScrollToItem(currentPage)
            }
        }
    }

    // 0dp must mean 0 — the old `else -> 6.dp` re-introduced a gap even when
    // the user explicitly set spacing to zero.
    val spacing = if (autoStitch) 0.dp else pageSpacing.coerceAtLeast(0).dp

    // ChapterId → header index for continuous ranges after the first.
    val headerForIndex: Map<Int, ReaderChapterRange> = remember(chapterRanges) {
        chapterRanges.drop(1).associateBy { it.startIndex }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier.fillMaxSize()
    ) {
        items(pages, key = { it.index }) { page ->
            Column(Modifier.fillMaxWidth()) {
                headerForIndex[page.index]?.let { range ->
                    NextChapterDivider(
                        chapterNumber = range.chapterNumber,
                        chapterTitle = range.chapterTitle
                    )
                }
                ZoomableMangaPage(
                    page = page,
                    imageFilter = imageFilter,
                    doubleTapZoomEnabled = doubleTapZoomEnabled,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun NextChapterDivider(chapterNumber: Float?, chapterTitle: String?) {
    val num = chapterNumber?.let {
        if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()
    }
    val cleanTitle = chapterTitle?.takeIf { it.isNotBlank() }
    val label = when {
        num != null && cleanTitle != null -> stringResource(R.string.fmt_055, num, cleanTitle)
        num != null -> stringResource(R.string.fmt_059, num)
        cleanTitle != null -> cleanTitle
        else -> stringResource(R.string.reader_next)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MangaColors.Primary.copy(alpha = 0.4f))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.weight(1f), color = MangaColors.Primary.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualPageReader(
    pages: List<ChapterPage>,
    rtl: Boolean,
    initialPage: Int,
    currentPage: Int,
    imageFilter: ReaderImageFilter,
    onPageChanged: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {},
    pageSpacing: Int = 0,
    doubleTapZoomEnabled: Boolean = true
) {
    val orderedPages = if (rtl) pages.reversed() else pages
    val spreadPages = orderedPages.chunked(2)
    // Spread index that contains the ViewModel's global currentPage.
    fun spreadForGlobal(global: Int): Int {
        val orderedIndex = if (rtl) orderedPages.size - 1 - global else global
        return (orderedIndex / 2).coerceIn(0, maxOf(0, spreadPages.size - 1))
    }
    val initialSpread = spreadForGlobal(initialPage)
    val pagerState = rememberPagerState(initialPage = initialSpread) { spreadPages.size }

    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (spreadPages.isEmpty()) return@LaunchedEffect
        val spread = spreadPages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        // Report the first page of the spread as the logical position.
        val orderedIndex = orderedPages.indexOfFirst { it.index == spread.firstOrNull()?.index }
        if (orderedIndex != -1) {
            val realIndex = if (rtl) orderedPages.size - 1 - orderedIndex else orderedIndex
            onPageChanged(realIndex.coerceIn(0, maxOf(0, pages.size - 1)))
        }
    }

    // Volume / tap-zone / slider changes update currentPage without swiping —
    // animate the pager so the visible spread follows the ViewModel.
    LaunchedEffect(currentPage, pages.size) {
        if (spreadPages.isEmpty()) return@LaunchedEffect
        val target = spreadForGlobal(currentPage.coerceIn(0, maxOf(0, pages.size - 1)))
        if (target != pagerState.currentPage) {
            runCatching { pagerState.animateScrollToPage(target) }
        }
    }

    // 0dp must mean 0 — no hardcoded 2.dp gap.
    val gap = pageSpacing.coerceAtLeast(0).dp

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { spreadIndex ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            spreadPages.getOrNull(spreadIndex)?.forEach { page ->
                ZoomableMangaPage(
                    page = page,
                    imageFilter = imageFilter,
                    doubleTapZoomEnabled = doubleTapZoomEnabled,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
            if ((spreadPages.getOrNull(spreadIndex)?.size ?: 0) == 1) {
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
    currentPage: Int = initialPage,
    imageFilter: ReaderImageFilter,
    onPageChanged: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: () -> Unit = {},
    doubleTapZoomEnabled: Boolean = true
) {
    val orderedPages = if (rtl) pages.reversed() else pages
    fun pagerIndexForGlobal(global: Int): Int =
        if (rtl && orderedPages.isNotEmpty()) {
            (orderedPages.size - 1 - global).coerceIn(0, maxOf(0, orderedPages.size - 1))
        } else {
            global.coerceIn(0, maxOf(0, orderedPages.size - 1))
        }
    val adjustedInitial = pagerIndexForGlobal(initialPage)
    val pagerState = rememberPagerState(initialPage = adjustedInitial) { orderedPages.size }

    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (orderedPages.isEmpty()) return@LaunchedEffect
        val realIndex = if (rtl) orderedPages.size - 1 - pagerState.currentPage else pagerState.currentPage
        onPageChanged(realIndex.coerceIn(0, maxOf(0, pages.size - 1)))
    }

    // External page changes (volume keys, side taps, bottom slider) must move
    // the pager — previously onPageChanged only updated state, leaving the
    // visible page behind so volume appeared broken.
    LaunchedEffect(currentPage, pages.size) {
        if (orderedPages.isEmpty()) return@LaunchedEffect
        val target = pagerIndexForGlobal(currentPage.coerceIn(0, maxOf(0, pages.size - 1)))
        if (target != pagerState.currentPage) {
            runCatching { pagerState.animateScrollToPage(target) }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { pageIndex ->
        ZoomableMangaPage(
            page = orderedPages[pageIndex],
            imageFilter = imageFilter,
            doubleTapZoomEnabled = doubleTapZoomEnabled,
            onTap = onTap,
            onLongPress = onLongPress,
            modifier = Modifier.fillMaxSize()
        )
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
            contentDescription = stringResource(R.string.accessibility_page, page.index + 1),
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
                Text(stringResource(R.string.fmt_076, page.index + 1),
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
    onRetryDownload: () -> Unit,
    canRetry: Boolean,
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
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.reader_page_counter, "$currentPage", "$totalPages"),
                        style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(stringResource(R.string.reader_live_count, liveReaders), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
                    Icon(Icons.Filled.NavigateBefore, stringResource(R.string.reader_previous), tint = Color.White)
                }
                IconButton(onClick = onNextChapter, enabled = hasNextChapter) {
                    Icon(Icons.Filled.NavigateNext, stringResource(R.string.reader_next), tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.settings), tint = Color.White)
                }
                if (canRetry) {
                    IconButton(onClick = onRetryDownload) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.retry_short), tint = Color.White)
                    }
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
    onToggleBookmark: () -> Unit,
    onEditNote: () -> Unit,
    onBrowseAnnotations: () -> Unit,
    communityEnabled: Boolean,
    onOpenComments: () -> Unit
) {
    var expandedSection by remember { mutableStateOf<String?>("actions") }

    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Quick Actions
        SectionHeader(stringResource(R.string.quick_actions_alt), "actions", expandedSection, { expandedSection = it }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = if (state.currentPage in state.bookmarkedPages) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    label = stringResource(R.string.bookmark),
                    onClick = onToggleBookmark
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.download),
                    onClick = onDownload,
                    enabled = !state.downloadInProgress
                )
                if (state.downloadInProgress) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Close,
                        label = stringResource(R.string.cancel),
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
                    label = stringResource(R.string.note),
                    onClick = onEditNote
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.FormatListBulleted,
                    label = stringResource(R.string.bookmarks),
                    onClick = onBrowseAnnotations
                )
                if (communityEnabled) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Forum,
                        label = stringResource(R.string.discussion),
                        onClick = onOpenComments
                    )
                }
            }
        }

        // Reading Mode Section
        // v8 (#2): SegmentedButtons crushed Arabic labels into vertical
        // one-character-per-line boxes. Scrollable glass chips keep every
        // label on one line and match the design system.
        SectionHeader(stringResource(R.string.reading_mode), "mode", expandedSection, { expandedSection = it }) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.readerMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label, maxLines = 1) },
                        shape = RoundedCornerShape(100.dp)
                    )
                }
            }
        }

        // Image Filter Section
        SectionHeader(stringResource(R.string.str_343), "filter", expandedSection, { expandedSection = it }) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderImageFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.imageFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label, maxLines = 1) },
                        shape = RoundedCornerShape(100.dp)
                    )
                }
            }
        }

        // Brightness Section
        SectionHeader(stringResource(R.string.brightness), "brightness", expandedSection, { expandedSection = it }) {
            Slider(value = state.brightness, onValueChange = onBrightnessChange, valueRange = 0.05f..1f)
            Text("${(state.brightness * 100).toInt()}%", color = MangaColors.Muted)
        }

        // Page Spacing
        SectionHeader(stringResource(R.string.page_spacing), "spacing", expandedSection, { expandedSection = it }) {
            Slider(
                value = state.pageSpacing.toFloat(),
                onValueChange = { onPageSpacingChange(it.toInt()) },
                valueRange = 0f..30f,
                steps = 6
            )
            Text("${state.pageSpacing}dp", color = MangaColors.Muted)
        }

        // Reading Options
        SectionHeader(stringResource(R.string.reading_options), "reading", expandedSection, { expandedSection = it }) {
            SwitchRow(stringResource(R.string.incognito), state.incognitoMode, onIncognitoChange)
            SwitchRow(stringResource(R.string.auto_next_chapter_alt), state.autoOpenNextChapter, onAutoNextChange)
            SwitchRow(stringResource(R.string.keep_screen_on), state.keepScreenOn, onKeepScreenOnChange)
            SwitchRow(stringResource(R.string.show_page_number), state.showPageNumber, onShowPageNumberChange)
            SwitchRow(stringResource(R.string.smart_preload), state.smartPrefetchEnabled, onSmartPrefetchChange)
        }

        // Overlays
        SectionHeader(stringResource(R.string.top_layers), "overlays", expandedSection, { expandedSection = it }) {
            SwitchRow(stringResource(R.string.show_reader_count), state.showLiveReadersOverlay, onLiveReadersChange)
            SwitchRow(stringResource(R.string.show_interactions), state.showReactionOverlay, onReactionsChange)
        }

        // Display
        SectionHeader(stringResource(R.string.width), "display", expandedSection, { expandedSection = it }) {
            SwitchRow(stringResource(R.string.str_445), state.dualPageLandscape, onDualPageChange)
            SwitchRow(stringResource(R.string.str_280), state.webtoonAutoStitch, onWebtoonStitchChange)
        }

        // Gestures
        SectionHeader(stringResource(R.string.actions), "gestures", expandedSection, { expandedSection = it }) {
            SwitchRow(stringResource(R.string.str_287), state.volumeButtonPageTurn, onVolumeButtonChange)
            SwitchRow(stringResource(R.string.double_tap_zoom), state.doubleTapZoom, onDoubleTapZoomChange)
            SwitchRow(stringResource(R.string.haptic_feedback), state.hapticsEnabled, onHapticsChange)
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
    // Row-level toggleable semantics: one focus target, correct Role for TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MangaColors.OnSurface)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ReaderCommentsSheet(
    comments: List<CommunityComment>,
    collapseSpoilersByDefault: Boolean,
    isSignedIn: Boolean,
    commentText: String,
    onCommentTextChange: (String) -> Unit,
    spoiler: Boolean,
    onSpoilerChange: (Boolean) -> Unit,
    errorMessage: String?,
    onSend: () -> Unit,
    onOpenCommunity: () -> Unit
) {
    val expandedSpoilers = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.str_230), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenCommunity) { Text(stringResource(R.string.str_327)) }
        }
        if (comments.isEmpty()) {
            Text(stringResource(R.string.str_363), color = MangaColors.Muted)
        } else {
            comments.takeLast(20).forEach { comment ->
                Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(comment.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                        if (comment.spoiler && collapseSpoilersByDefault && comment.id !in expandedSpoilers) {
                            TextButton(onClick = { expandedSpoilers.add(comment.id) }) { Text(stringResource(R.string.community_show_spoiler)) }
                        } else {
                            Text(comment.text, color = MangaColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (isSignedIn) {
            OutlinedTextField(value = commentText, onValueChange = onCommentTextChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.add_comment)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spoiler, onCheckedChange = onSpoilerChange)
                    Text(stringResource(R.string.community_spoiler))
                }
                Button(onClick = onSend, enabled = commentText.isNotBlank()) { Text(stringResource(R.string.community_send)) }
            }
            errorMessage?.let { msg ->
                Text(msg, color = MangaColors.Error, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            // Guests: composer hidden — Firestore rules would reject the write anyway.
            Text(
                stringResource(R.string.reader_sign_in_to_participate),
                color = MangaColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
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
private fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    showPageNumber: Boolean,
    onPageSelected: (Int) -> Unit
) {
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
        if (showPageNumber) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text("${currentPage + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    Text("$totalPages", style = MaterialTheme.typography.labelSmall, color = Color(0x88FFFFFF))
                }
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
            Text(stringResource(R.string.loading_chapter),
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
            Text(stringResource(R.string.str_333), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onBack,
                border = androidx.compose.foundation.BorderStroke(1.dp, MangaColors.OutlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.back))
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
            Text(stringResource(R.string.search_cloudflare_required), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.fmt_062, domain), style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSolve) { Text(stringResource(R.string.str_326)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
    }
}
