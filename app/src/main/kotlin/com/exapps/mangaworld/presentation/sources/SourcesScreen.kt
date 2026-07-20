
package com.exapps.mangaworld.presentation.sources
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onSourceClick: (sourceId: String) -> Unit = {},
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSource by remember { mutableStateOf<MangaSource?>(null) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_sources), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
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
                items(MangaSource.entries) { source ->
                    SourceGridCard(
                        source = source,
                        isEnabled = state.enabledSources[source.id] != false,
                        onClick = { onSourceClick(source.id) },
                        onLongClick = { selectedSource = source }
                    )
                }
            }
        }
    }

    // Source Settings Bottom Sheet
    selectedSource?.let { source ->
        SourceSettingsSheet(
            source = source,
            isEnabled = state.enabledSources[source.id] != false,
            isNotificationEnabled = state.notificationStates[source.id] != false,
            onToggleEnabled = { enabled -> viewModel.toggleSource(source.id, enabled) },
            onToggleNotification = { enabled -> viewModel.toggleSourceNotification(source.id, enabled) },
            onClearCookies = { viewModel.clearCookies(source) },
            onDismiss = { selectedSource = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceGridCard(
    source: MangaSource,
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
                if (source.logoDrawableRes != 0) {
                    Image(
                        painter = painterResource(id = source.logoDrawableRes),
                        contentDescription = source.displayName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (!isEnabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = source.displayName.take(2),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = sourceColor
                    )
                }
            }

            // Source name
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled) MangaColors.OnSurface else MangaColors.Muted,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )

            // Domain hint
            Text(
                text = source.baseUrl.removePrefix("https://").removePrefix("http://").take(18),
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
        }
    }
}

private fun getSourceColor(source: MangaSource): Color = when (source.themeType) {
    MangaSource.ThemeType.MADARA -> MangaColors.Pink
    MangaSource.ThemeType.MANGAREADER -> MangaColors.Cyan
    MangaSource.ThemeType.ASTRO -> MangaColors.Primary
    MangaSource.ThemeType.API -> MangaColors.Green
    MangaSource.ThemeType.CUSTOM -> MangaColors.Yellow
    MangaSource.ThemeType.MADARA_CUSTOM -> MangaColors.Orange
    MangaSource.ThemeType.OTHER -> MangaColors.Muted
}

