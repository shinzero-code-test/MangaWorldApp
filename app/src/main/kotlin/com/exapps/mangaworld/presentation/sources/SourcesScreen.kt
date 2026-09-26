package com.exapps.mangaworld.presentation.sources
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourceUiEntry
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onSourceClick: (sourceId: String) -> Unit = {},
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val heldDetails by viewModel.heldDetails.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // One-shot result notices (approve / re-check outcomes).
    LaunchedEffect(Unit) {
        viewModel.notice.collect { resId ->
            runCatching { snackbar.showSnackbar(context.getString(resId)) }
        }
    }

    Scaffold(
        containerColor = MangaColors.Background,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MangaColors.Surface,
                    contentColor = MangaColors.OnSurface
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_sources), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                // v9.1.1: the on-demand sync lane finally has a trigger —
                // without it only the 24h worker could fetch new sources.
                actions = {
                    IconButton(onClick = { viewModel.checkForUpdates() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            stringResource(R.string.plugin_check_updates),
                            tint = MangaColors.OnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            // Section header
            Text(
                stringResource(R.string.arabic_sources),
                style = MaterialTheme.typography.titleMedium,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Hint
            Text(
                stringResource(R.string.long_press_source_settings),
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.Muted,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Unified grid for ALL sources
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(entries, key = { it.id }) { source ->
                    SourceGridCard(
                        source = source,
                        isEnabled = state.enabledSources[source.id] != false,
                        onClick = { onSourceClick(source.id) },
                        onLongClick = { selectedSourceId = source.id }
                    )
                }
            }
        }
    }

    // Source Settings Bottom Sheet
    selectedSourceId?.let { sourceId ->
        entries.firstOrNull { it.id == sourceId }?.let { source ->
            SourceSettingsSheet(
                source = source,
                baseUrl = viewModel.baseUrlFor(sourceId),
                isEnabled = state.enabledSources[sourceId] != false,
                isNotificationEnabled = state.notificationStates[sourceId] != false,
                onToggleEnabled = { enabled -> viewModel.toggleSource(sourceId, enabled) },
                onToggleNotification = { enabled -> viewModel.toggleSourceNotification(sourceId, enabled) },
                onClearCookies = { viewModel.clearCookies(sourceId) },
                onDismiss = { selectedSourceId = null },
                detail = heldDetails[sourceId],
                busy = busyId == sourceId,
                onApprove = { viewModel.approve(sourceId) },
                onRecheck = { viewModel.recheck(sourceId) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceGridCard(
    source: SourceUiEntry,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val sourceColor = getSourceColor(source)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Real site logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                sourceColor.copy(alpha = 0.15f),
                                MangaColors.SurfaceContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (source.logoRes != 0) {
                    Image(
                        painter = painterResource(id = source.logoRes),
                        contentDescription = source.name,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (!isEnabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = source.name.take(2),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = sourceColor
                    )
                }
            }

            // Source name
            Text(
                text = source.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled) MangaColors.OnSurface else MangaColors.Muted,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )

            // Domain hint (effective — follows Remote Config overrides)
            Text(
                text = source.hostHint.take(18),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MangaColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // CF badge if protected
            if (source.requiresVerification) {
                Text(
                    "CF",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Yellow,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }

            // Disabled badge
            if (!isEnabled) {
                Text(
                    stringResource(R.string.disabled_alt),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Error,
                    fontWeight = FontWeight.Bold
                )
            }

            // Consent posture badge (v9.1.0): held/quarantined rows explain why
            // they don't serve; details + actions live in the settings sheet.
            when (source.rowState) {
                PluginRowState.HELD -> Text(
                    stringResource(R.string.plugin_needs_approval),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Yellow,
                    fontWeight = FontWeight.Bold
                )
                PluginRowState.QUARANTINED -> Text(
                    stringResource(R.string.plugin_quarantined),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Error,
                    fontWeight = FontWeight.Bold
                )
                PluginRowState.SERVING -> Unit
            }
        }
    }
}

private fun getSourceColor(source: SourceUiEntry): Color = when (source.engine) {
    SourceEngine.MADARA -> MangaColors.Pink
    SourceEngine.MANGAREADER -> MangaColors.Cyan
    SourceEngine.ASTRO -> MangaColors.Primary
    SourceEngine.API -> MangaColors.Green
    SourceEngine.CUSTOM -> MangaColors.Yellow
    SourceEngine.SCRIPT -> MangaColors.Orange
}
