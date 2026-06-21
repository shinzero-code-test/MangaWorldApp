package com.exapps.mangaworld.presentation.sources

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity

/**
 * Dedicated browse screen for a single manga source.
 * Shows manga list with advanced search, genre filters, and sort options.
 * Auto-triggers Cloudflare solver when CF challenge is detected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: String,
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    onBack: () -> Unit,
    viewModel: SourceBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSortSheet by remember { mutableStateOf(false) }

    val cfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.dismissCloudflare()
        }
    }

    // Auto-trigger CF solver
    LaunchedEffect(uiState.needsCloudflare) {
        if (uiState.needsCloudflare) {
            val intent = Intent(context, WebViewSolverActivity::class.java).apply {
                putExtra(WebViewSolverActivity.EXTRA_URL, uiState.source.baseUrl)
                putExtra(WebViewSolverActivity.EXTRA_DOMAIN, java.net.URI(uiState.source.baseUrl).host)
            }
            cfLauncher.launch(intent)
        }
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = uiState.source.logoDrawableRes,
                            contentDescription = uiState.source.displayName,
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(uiState.source.displayName, color = MangaColors.OnSurface)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.Filled.Sort, "ترتيب", tint = MangaColors.Cyan)
                    }
                    IconButton(onClick = {
                        viewModel.setStatus(
                            when (uiState.selectedStatus) {
                                null -> MangaStatus.ONGOING
                                MangaStatus.ONGOING -> MangaStatus.COMPLETED
                                MangaStatus.COMPLETED -> null
                                else -> null
                            }
                        )
                    }) {
                        Icon(Icons.Filled.FilterList, "تصفيات", tint = MangaColors.OnSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = {
                    Text("ابحث في ${uiState.source.displayName}...", color = MangaColors.Muted,
                        style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MangaColors.Primary) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, null, tint = MangaColors.Muted)
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MangaColors.SurfaceHigh,
                    focusedBorderColor = MangaColors.Primary,
                    cursorColor = MangaColors.Primary
                ),
                singleLine = true
            )

            // Status chips row
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    StatusFilterChip(
                        selected = uiState.selectedStatus == null,
                        onClick = { viewModel.setStatus(null) },
                        text = "الكل"
                    )
                }
                item {
                    StatusFilterChip(
                        selected = uiState.selectedStatus == MangaStatus.ONGOING,
                        onClick = { viewModel.setStatus(MangaStatus.ONGOING) },
                        text = "مستمر"
                    )
                }
                item {
                    StatusFilterChip(
                        selected = uiState.selectedStatus == MangaStatus.COMPLETED,
                        onClick = { viewModel.setStatus(MangaStatus.COMPLETED) },
                        text = "مكتمل"
                    )
                }
            }

            // Cloudflare banner
            if (uiState.needsCloudflare) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Yellow.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Shield, null, tint = MangaColors.Yellow)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "يتطلب التحقق من الهوية — جاري فتح نافذة التحقق",
                            color = MangaColors.Yellow,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Error banner
            uiState.errorText?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Error.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MangaColors.Error)
                        Spacer(Modifier.width(8.dp))
                        Text(error, color = MangaColors.Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Manga grid
            if (uiState.mangaList.isEmpty() && uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MangaColors.Cyan)
                }
            } else if (uiState.mangaList.isEmpty() && !uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Search, null, tint = MangaColors.Muted, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (uiState.query.isNotBlank()) "لا توجد نتائج" else "اضغط للبحث أو انتظر التحميل",
                            color = MangaColors.OnSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.mangaList, key = { it.id }) { manga ->
                        SourceMangaCard(
                            manga = manga,
                            onClick = { onMangaClick(uiState.source.id, manga.slug) }
                        )
                    }

                    // Load more button
                    if (uiState.hasMore && uiState.mangaList.isNotEmpty()) {
                        item(span = { GridItemSpan(3) }) {
                            if (uiState.isLoading) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MangaColors.Cyan)
                                }
                            } else {
                                TextButton(
                                    onClick = { viewModel.loadMore() },
                                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                                ) {
                                    Text("حمّل المزيد", color = MangaColors.Cyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Sort bottom sheet
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            containerColor = MangaColors.Surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("ترتيب حسب", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                com.exapps.mangaworld.domain.model.SortBy.entries.forEach { sort ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.setSortBy(sort)
                            showSortSheet = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (uiState.sortBy == sort) Icons.Filled.RadioButtonChecked
                            else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (uiState.sortBy == sort) MangaColors.Cyan else MangaColors.Muted
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(sort.label, color = MangaColors.OnSurface)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusFilterChip(selected: Boolean, onClick: () -> Unit, text: String) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (selected) MangaColors.Primary.copy(alpha = 0.2f) else MangaColors.SurfaceContainer,
            labelColor = if (selected) MangaColors.Cyan else MangaColors.OnSurfaceVariant
        )
    )
}

@Composable
private fun SourceMangaCard(manga: MangaItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.65f).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            AsyncImage(
                model = manga.coverUrl.ifBlank { null },
                contentDescription = manga.title,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.padding(6.dp)) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (manga.status != MangaStatus.UNKNOWN) {
                    Text(
                        manga.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaColors.Muted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
