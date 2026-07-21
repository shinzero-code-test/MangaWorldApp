package com.exapps.mangaworld.presentation.suggestions
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.core.data.RecommendationEngine
import com.exapps.mangaworld.core.data.SuggestionsManager
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val recommendationEngine: RecommendationEngine,
    private val suggestionsManager: SuggestionsManager,
    private val cacheDao: MangaCacheDao
) : ViewModel() {

    private val _suggestions = mutableStateListOf<MangaItem>()
    val suggestions: List<MangaItem> get() = _suggestions
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun loadSuggestions() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                // Load cached manga as candidates for recommendations
                val cachedMangas = cacheDao.getAll(200).mapNotNull { cache ->
                    try {
                        MangaItem(
                            id = cache.mangaId,
                            slug = cache.slug,
                            title = cache.title,
                            coverUrl = cache.coverUrl,
                            source = MangaSource.fromId(cache.sourceId),
                            genres = try {
                                org.json.JSONArray(cache.genresJson).let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                }
                            } catch (_: Exception) { emptyList() },
                            status = try {
                                com.exapps.mangaworld.domain.model.MangaStatus.valueOf(cache.statusStr)
                            } catch (_: Exception) { com.exapps.mangaworld.domain.model.MangaStatus.UNKNOWN },
                            type = try {
                                com.exapps.mangaworld.domain.model.MangaType.valueOf(cache.typeStr)
                            } catch (_: Exception) { com.exapps.mangaworld.domain.model.MangaType.UNKNOWN },
                            rating = cache.rating,
                            latestChapter = cache.latestChapter,
                            totalChapters = cache.totalChapters,
                            url = cache.url
                        )
                    } catch (_: Exception) { null }
                }

                if (cachedMangas.isEmpty()) {
                    errorMessage = stringResource(R.string.str_367)
                    isLoading = false
                    return@launch
                }

                val recommendations = recommendationEngine.getSmartRecommendations(cachedMangas, limit = 20)
                _suggestions.clear()
                _suggestions.addAll(recommendations)

                // Update persistent suggestions
                val mangaSuggestions = recommendations.map { manga ->
                    com.exapps.mangaworld.core.data.MangaSuggestion(
                        mangaId = manga.id,
                        title = manga.title,
                        sourceId = manga.source.id,
                        relevance = 0.5f
                    )
                }
                suggestionsManager.updateSuggestions(mangaSuggestions)
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.fmt_075, e.message ?: "")
            }
            isLoading = false
        }
    }

    init {
        loadSuggestions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(
    onBack: () -> Unit,
    onMangaClick: (source: MangaSource, slug: String) -> Unit,
    viewModel: SuggestionsViewModel = hiltViewModel()
) {
    val suggestions = viewModel.suggestions
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.str_116), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadSuggestions() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.update), tint = MangaColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MangaColors.Primary
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("😅", style = MaterialTheme.typography.displayMedium)
                        Text(errorMessage, color = MangaColors.OnSurfaceVariant)
                        Button(
                            onClick = { viewModel.loadSuggestions() },
                            colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary)
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                suggestions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("📭", style = MaterialTheme.typography.displayMedium)
                        Text(stringResource(R.string.no_suggestions_yet), color = MangaColors.OnSurfaceVariant)
                        Text(stringResource(R.string.str_224), color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                stringResource(R.string.based_on_reading_taste),
                                style = MaterialTheme.typography.titleSmall,
                                color = MangaColors.OnSurfaceVariant
                            )
                        }
                        items(suggestions) { manga ->
                            SuggestionCard(manga = manga) {
                                onMangaClick(manga.source, manga.slug)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(manga: MangaItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(manga.coverUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = LocalContext.current.imageLoader,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp, 110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MangaColors.SurfaceContainer)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MangaColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (manga.genres.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        manga.genres.take(3).forEach { genre ->
                            Text(
                                genre,
                                style = MaterialTheme.typography.labelSmall,
                                color = MangaColors.Cyan,
                                modifier = Modifier
                                    .background(MangaColors.Cyan.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                manga.rating?.let { rating ->
                    Text(
                        "⭐ ${String.format("%.1f", rating)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.Yellow
                    )
                }
                Text(
                    manga.source.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Muted
                )
            }
        }
    }
}
