package com.exapps.mangaworld.presentation.picker
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors

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
