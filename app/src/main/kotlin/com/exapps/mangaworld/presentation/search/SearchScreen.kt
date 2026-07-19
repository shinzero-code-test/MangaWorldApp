import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.search

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity

@Composable
fun SearchScreen(
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val enabledSources by viewModel.enabledSources.collectAsStateWithLifecycle()
    val requiresVerification by viewModel.selectedSourceRequiresVerification.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val cfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data?.getStringExtra(WebViewSolverActivity.RESULT_COOKIES).orEmpty()
            val domain = result.data?.getStringExtra(WebViewSolverActivity.EXTRA_DOMAIN).orEmpty()
            if (cookies.isNotBlank() && domain.isNotBlank()) {
                CookieCache.put(domain, cookies)
                viewModel.saveCookies(domain, cookies)
                viewModel.reload()
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        // ── Search bar ───────────────────────────────────────────────────────
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.search_hint), color = MangaColors.Muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MangaColors.Primary) },
            trailingIcon = {
                if (query.isNotEmpty())
                    IconButton(onClick = { viewModel.clear(); keyboard?.show() }) {
                        Icon(Icons.Filled.Clear, null, tint = MangaColors.Muted)
                    }
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MangaColors.Primary,
                unfocusedBorderColor = MangaColors.OutlineVariant,
                focusedContainerColor = MangaColors.SurfaceContainer,
                unfocusedContainerColor = MangaColors.SurfaceContainer,
                cursorColor = MangaColors.Primary,
                focusedTextColor = MangaColors.OnSurface,
                unfocusedTextColor = MangaColors.OnSurface
            ),
            singleLine = true
        )

        // ── Source filter ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) {
                Text(source?.displayName ?: stringResource(R.string.search_all_sources), color = MangaColors.Cyan)
                Icon(Icons.Filled.ArrowDropDown, null, tint = MangaColors.Cyan)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_all_sources)) },
                    onClick = { viewModel.setSource(null); expanded = false }
                )
                enabledSources.forEach { src ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(src.displayName)
                                if (src.requiresVerification) {
                                    Icon(
                                        Icons.Filled.Shield, null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MangaColors.Muted
                                    )
                                }
                            }
                        },
                        onClick = { viewModel.setSource(src); expanded = false }
                    )
                }
            }
        }

        // ── Cloudflare warning banner ─────────────────────────────────────────
        if (requiresVerification) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(Color(0x22FFA500), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Shield, null,
                    modifier = Modifier.size(16.dp), tint = MangaColors.Yellow)
                Text(
                    stringResource(R.string.str_434) +
                    stringResource(R.string.str_455),
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Yellow,
                    modifier = Modifier.weight(1f)
                )
                source?.let { selectedSource ->
                    TextButton(onClick = {
                        cfLauncher.launch(
                            Intent(context, WebViewSolverActivity::class.java)
                                .putExtra(WebViewSolverActivity.EXTRA_URL, selectedSource.baseUrl)
                                .putExtra(
                                    WebViewSolverActivity.EXTRA_DOMAIN,
                                    selectedSource.baseUrl.removePrefix("https://").removePrefix("http://")
                                )
                        )
                    }) {
                        Text(stringResource(R.string.search_cloudflare_button), color = MangaColors.Yellow)
                    }
                }
            }
        }

        // ── Body ─────────────────────────────────────────────────────────────
        when {
            query.isEmpty() -> SearchHints(onSuggestionClick = viewModel::setQuery)
            query.length < 2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.finish_typing), color = MangaColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
            else -> SearchResults(query = query, onMangaClick = onMangaClick, viewModel = viewModel)
        }
    }
}

@Composable
private fun SearchHints(onSuggestionClick: (String) -> Unit) {
    val suggestions = listOf(
        "Solo Leveling", "Nano Machine", "Tower of God",
        "One Piece", "Black Clover", "Naruto", stringResource(R.string.manhwa_action), stringResource(R.string.genre_romance)
    )
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.search_suggested), style = MaterialTheme.typography.titleSmall,
            color = MangaColors.Muted, modifier = Modifier.padding(bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions) { s -> SuggestionChip(label = s, onClick = { onSuggestionClick(s) }) }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(onClick = onClick)
            .background(MangaColors.SurfaceContainer, RoundedCornerShape(100.dp))
            .border(1.dp, MangaColors.OutlineVariant, RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MangaColors.MutedLight)
    }
}

@Composable
private fun SearchResults(
    query: String,
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    viewModel: SearchViewModel
) {
    val pagingItems = viewModel.results.collectAsLazyPagingItems()

    when (pagingItems.loadState.refresh) {
        is LoadState.Loading -> MangaLoadingIndicator(Modifier.padding(32.dp))
        is LoadState.Error -> {
            val e = (pagingItems.loadState.refresh as LoadState.Error).error
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = if (SearchViewModel.isCloudflareCause(e)) stringResource(R.string.search_cloudflare_required)
                        else stringResource(R.string.search_error),
                subtitle = if (SearchViewModel.isCloudflareCause(e))
                    stringResource(R.string.str_113)
                else stringResource(R.string.str_210),
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> {
            if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh !is LoadState.Loading) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.str_370)$query\"",
                    subtitle = stringResource(R.string.try_different_keywords),
                    modifier = Modifier.fillMaxSize()
                )
                return
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(pagingItems.itemCount, key = { i -> pagingItems.peek(i)?.let { "${it.source.id}_${it.slug}" } ?: "item_$i" }) { i ->
                    val manga = pagingItems[i] ?: return@items
                    SearchResultItem(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        type = manga.type,
                        status = manga.status,
                        genres = manga.genres,
                        source = manga.source,
                        onClick = { onMangaClick(manga.source.id, manga.slug) }
                    )
                }
                if (pagingItems.loadState.append is LoadState.Loading) {
                    item { MangaLoadingIndicator(Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    title: String,
    coverUrl: String,
    type: com.exapps.mangaworld.domain.model.MangaType,
    status: com.exapps.mangaworld.domain.model.MangaStatus,
    genres: List<String>,
    source: com.exapps.mangaworld.domain.model.MangaSource,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MangaColors.CardBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MangaCover(url = coverUrl, contentDescription = title,
            modifier = Modifier.size(64.dp, 90.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MangaColors.OnSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (type != com.exapps.mangaworld.domain.model.MangaType.UNKNOWN) TypeBadge(type)
                StatusBadge(status)
            }
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(genres.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            SourceBadge(source)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MangaColors.OutlineVariant)
    }
}
