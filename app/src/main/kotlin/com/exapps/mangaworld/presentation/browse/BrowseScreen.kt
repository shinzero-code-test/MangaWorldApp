package com.exapps.mangaworld.presentation.browse

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.SortBy
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun BrowseScreen(
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagingItems = viewModel.mangaFlow.collectAsLazyPagingItems()
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        // Search bar
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = {
                Text("ابحث عن مانجا...", color = MangaColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MangaColors.Primary) },
            trailingIcon = {
                if (uiState.query.isNotEmpty())
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Clear, null, tint = MangaColors.Muted)
                    }
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MangaColors.Primary,
                unfocusedBorderColor = MangaColors.OutlineVariant,
                unfocusedContainerColor = MangaColors.SurfaceContainer,
                focusedContainerColor = MangaColors.SurfaceContainer,
                cursorColor = MangaColors.Primary,
                focusedTextColor = MangaColors.OnSurface,
                unfocusedTextColor = MangaColors.OnSurface
            ),
            singleLine = true
        )

        // Genre chips
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.genres.size, key = { uiState.genres[it] }) { i ->
                val genre = uiState.genres[i]
                val isAll = genre == "الكل"
                val isSelected = if (isAll) uiState.selectedGenre == null
                                 else uiState.selectedGenre == genre
                GenreChip(
                    label = genre,
                    selected = isSelected,
                    onClick = {
                        viewModel.setGenre(if (isAll) null else genre)
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = uiState.selectedSource == null,
                    onClick = { viewModel.setSource(null) },
                    label = { Text("كل المصادر") }
                )
            }
            items(MangaSource.entries.size, key = { MangaSource.entries[it].id }) { index ->
                val src = MangaSource.entries[index]
                if (uiState.enabledSourceIds.contains(src.id)) {
                    FilterChip(
                        selected = uiState.selectedSource == src,
                        onClick = { viewModel.setSource(src) },
                        label = { Text(src.displayName) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SortBy.entries.size, key = { SortBy.entries[it].name }) { i ->
                val sort = SortBy.entries[i]
                FilterChip(
                    selected = uiState.sortBy == sort,
                    onClick = { viewModel.setSortBy(sort) },
                    label = { Text(sort.label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Filter/Sort row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showFilterSheet = true }) {
                Icon(Icons.Filled.FilterList, null,
                    tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("تصفية", color = MangaColors.Cyan,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pagingItems.itemCount} نتيجة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.Muted
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = viewModel::toggleView) {
                    Icon(
                        if (uiState.isGridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        null, tint = MangaColors.PrimaryLight
                    )
                }
            }
        }

        // Grid
        LazyVerticalGrid(
            columns = if (uiState.isGridView) GridCells.Fixed(2) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(pagingItems.itemCount) { index ->
                val manga = pagingItems[index] ?: return@items
                if (uiState.isGridView) {
                    MangaCard(
                        manga = manga,
                        onClick = { onMangaClick(manga.source.id, manga.slug) }
                    )
                } else {
                    BrowseListItem(
                        manga = manga,
                        onClick = { onMangaClick(manga.source.id, manga.slug) }
                    )
                }
            }

            // Loading footer
            pagingItems.apply {
                when {
                    loadState.append is LoadState.Loading -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            MangaLoadingIndicator(Modifier.padding(16.dp))
                        }
                    }
                    loadState.append is LoadState.Error -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { retry() },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("إعادة التحميل", color = MangaColors.Primary)
                            }
                        }
                    }
                    loadState.refresh is LoadState.Loading && itemCount == 0 -> {
                        items(6, span = { GridItemSpan(1) }) {
                            ShimmerBox(
                                Modifier.fillMaxWidth().height(220.dp),
                                RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            currentStatus = uiState.selectedStatus,
            currentType = uiState.selectedType,
            onDismiss = { showFilterSheet = false },
            onApply = { status, type ->
                viewModel.setStatus(status)
                viewModel.setType(type)
                showFilterSheet = false
            }
        )
    }
}

@Composable
private fun BrowseListItem(
    manga: com.exapps.mangaworld.domain.model.MangaItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MangaColors.CardBg, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MangaCover(
            url = manga.coverUrl,
            contentDescription = manga.title,
            modifier = Modifier.size(70.dp, 98.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(manga.title, style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurface, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (manga.type != com.exapps.mangaworld.domain.model.MangaType.UNKNOWN)
                    TypeBadge(manga.type)
                StatusBadge(manga.status)
            }
            Spacer(Modifier.height(4.dp))
            if (manga.latestChapter != null)
                Text("الفصل ${manga.latestChapter}", style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.PrimaryLight)
        }
        if (manga.rating != null)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MangaColors.Yellow
                )
                Text("%.1f".format(manga.rating), style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Muted)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    currentStatus: com.exapps.mangaworld.domain.model.MangaStatus?,
    currentType: com.exapps.mangaworld.domain.model.MangaType?,
    onDismiss: () -> Unit,
    onApply: (com.exapps.mangaworld.domain.model.MangaStatus?, com.exapps.mangaworld.domain.model.MangaType?) -> Unit
) {
    var selectedStatus by remember { mutableStateOf(currentStatus) }
    var selectedType by remember { mutableStateOf(currentType) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.SurfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MangaColors.OutlineVariant) }
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("تصفية النتائج", style = MaterialTheme.typography.titleMedium,
                color = MangaColors.OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            Text("الحالة", style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "الكل",
                    com.exapps.mangaworld.domain.model.MangaStatus.ONGOING to "مستمر",
                    com.exapps.mangaworld.domain.model.MangaStatus.COMPLETED to "مكتمل"
                ).forEach { (status, label) ->
                    FilterChip(
                        selected = selectedStatus == status,
                        onClick = { selectedStatus = status },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MangaColors.Primary,
                            selectedLabelColor = Color.White,
                            containerColor = MangaColors.SurfaceHigh,
                            labelColor = MangaColors.MutedLight
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("النوع / الفئة", style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "الكل",
                    com.exapps.mangaworld.domain.model.MangaType.MANGA to "مانجا",
                    com.exapps.mangaworld.domain.model.MangaType.MANHWA to "مانهوا"
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MangaColors.Primary,
                            selectedLabelColor = Color.White,
                            containerColor = MangaColors.SurfaceHigh,
                            labelColor = MangaColors.MutedLight
                        )
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            GradientButton(
                text = "تطبيق الفلتر",
                onClick = { onApply(selectedStatus, selectedType) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onApply(null, null) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MangaColors.Muted),
                border = BorderStroke(1.dp, MangaColors.OutlineVariant)
            ) {
                Text("إعادة تعيين")
            }
        }
    }
}
