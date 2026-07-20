package com.exapps.mangaworld.presentation.library
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun LibraryScreen(
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    onBrowseClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium,
                color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            if (state.activeTab == LibraryTab.HISTORY && state.history.isNotEmpty()) {
                IconButton(onClick = { showClearHistoryConfirm = true }) {
                    Icon(Icons.Filled.DeleteSweep, stringResource(R.string.clear_history), tint = MangaColors.Muted)
                }
            }
        }

        // Tabs
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryTab.entries.forEach { tab ->
                val active = state.activeTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) MangaColors.Primary else MangaColors.SurfaceContainer)
                        .clickable { viewModel.selectTab(tab) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else MangaColors.MutedLight
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Content
        when (state.activeTab) {
            LibraryTab.FAVORITES -> FavoritesContent(
                favorites = state.favorites,
                onMangaClick = { fav -> onMangaClick(fav.source.id, fav.slug) },
                onRemove = { viewModel.removeFavorite(it.mangaId) },
                onBrowse = onBrowseClick
            )
            LibraryTab.HISTORY -> HistoryContent(
                history = state.history,
                onMangaClick = { h -> onMangaClick(h.source.id, h.slug) },
                onRemove = { viewModel.removeHistory(it.mangaId) },
                onBrowse = onBrowseClick
            )
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            containerColor = MangaColors.Background,
            title = { Text(stringResource(R.string.clear_history), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.clear_history_confirm), color = MangaColors.OnSurfaceVariant) },
            confirmButton = {
                Button(onClick = { viewModel.clearHistory(); showClearHistoryConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)) {
                    Text(stringResource(R.string.delete), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = MangaColors.Muted)
                }
            }
        )
    }
}

// ─── Favorites Grid ───────────────────────────────────────────────────────────

@Composable
private fun FavoritesContent(
    favorites: List<FavoriteManga>,
    onMangaClick: (FavoriteManga) -> Unit,
    onRemove: (FavoriteManga) -> Unit,
    onBrowse: () -> Unit
) {
    if (favorites.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.BookmarkBorder,
            title = stringResource(R.string.library_empty_favorites),
            subtitle = stringResource(R.string.library_empty_favorites_hint),
            action = { GradientButton(stringResource(R.string.browse_manga), onBrowse) },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(favorites, key = { it.mangaId }) { fav ->
            FavoriteCard(fav = fav, onClick = { onMangaClick(fav) }, onRemove = { onRemove(fav) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(fav: FavoriteManga, onClick: () -> Unit, onRemove: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box {
            MangaCover(
                url = fav.coverUrl,
                contentDescription = fav.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.67f)
            )
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
            ) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.MoreVert, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_remove_favorite), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.BookmarkRemove, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { onRemove(); showMenu = false }
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(fav.title, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = MangaColors.OnSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (fav.totalChapters > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fav.progressPercent },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = MangaColors.Primary,
                    trackColor = MangaColors.OutlineVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(stringResource(R.string.fmt_026, fav.readChapters, fav.totalChapters),
                    style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
            }
        }
    }
}

// ─── History List ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryContent(
    history: List<ReadingHistoryItem>,
    onMangaClick: (ReadingHistoryItem) -> Unit,
    onRemove: (ReadingHistoryItem) -> Unit,
    onBrowse: () -> Unit
) {
    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.History,
            title = stringResource(R.string.library_empty_history),
            subtitle = stringResource(R.string.start_reading_to_appear),
            action = { GradientButton(stringResource(R.string.browse_manga), onBrowse) },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(history, key = { it.mangaId }) { item ->
            HistoryItem(item = item, onClick = { onMangaClick(item) }, onRemove = { onRemove(item) })
        }
    }
}

@Composable
private fun HistoryItem(item: ReadingHistoryItem, onClick: () -> Unit, onRemove: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MangaColors.CardBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MangaCover(url = item.coverUrl, contentDescription = item.title,
            modifier = Modifier.size(60.dp, 84.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MangaColors.OnSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.fmt_058, item.lastChapterNumber.let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() }),
                style = MaterialTheme.typography.bodySmall, color = MangaColors.PrimaryLight
            )
            if (item.totalChapters > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = MangaColors.Primary,
                    trackColor = MangaColors.OutlineVariant
                )
                Spacer(Modifier.height(2.dp))
                Text("${item.readChapters}/${item.totalChapters}",
                    style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, null, tint = MangaColors.Muted)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_from_history), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { onRemove(); showMenu = false }
                )
            }
        }
    }
}
