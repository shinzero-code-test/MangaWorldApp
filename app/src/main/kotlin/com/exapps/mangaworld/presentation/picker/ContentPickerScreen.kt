
package com.exapps.mangaworld.presentation.picker
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentPickerScreen(
    sources: List<MangaSource> = MangaSource.entries,
    onMangaSelected: (MangaItem) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<MangaSource?>(null) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_manga), color = MangaColors.OnSurface) },
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
                .padding(horizontal = 16.dp)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_manga)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            // Source selector
            Text(
                stringResource(R.string.source),
                style = MaterialTheme.typography.labelLarge,
                color = MangaColors.Cyan
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sources) { source ->
                    SourceChip(
                        source = source,
                        isSelected = source == selectedSource,
                        onClick = { selectedSource = if (selectedSource == source) null else source }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceChip(
    source: MangaSource,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(source.displayName) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MangaColors.Cyan.copy(alpha = 0.2f)
        )
    )
}

