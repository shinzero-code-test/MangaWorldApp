package com.exapps.mangaworld.presentation.latest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.align
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.components.EmptyState
import com.exapps.mangaworld.presentation.components.MangaCover
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LatestUpdatesUiState(
    val isLoading: Boolean = true,
    val items: List<LatestChapterItem> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class LatestUpdatesViewModel @Inject constructor(
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {
    private val _state = MutableStateFlow(LatestUpdatesUiState())
    val state: StateFlow<LatestUpdatesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val result = runCatching {
                val enabled = settingsRepository.getAppSettings().first().enabledSources
                val sources = MangaSource.entries.filter { it.id in enabled }
                coroutineScope {
                    sources.map { source ->
                        async { mangaRepository.getHomeData(source).getOrNull()?.latestChapters.orEmpty() }
                    }.awaitAll().flatten()
                }
                    .distinctBy { it.chapterUrl }
            }

            result.onSuccess { items ->
                _state.update { it.copy(isLoading = false, items = items) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message ?: "تعذر تحميل التحديثات") }
            }

            if (result.isSuccess) {
                widgetShortcutCoordinator.refreshWidgets()
            }
        }
    }
}

@Composable
fun LatestUpdatesScreen(
    onBack: () -> Unit,
    onOpenManga: (sourceId: String, slug: String) -> Unit,
    viewModel: LatestUpdatesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = MangaColors.OnSurface)
            }
            Text(
                text = "أحدث التحديثات",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MangaColors.OnSurface
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = MangaColors.OnSurface)
            }
        }

        when {
            state.isLoading -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .align(Alignment.CenterHorizontally),
                color = MangaColors.Primary
            )
            state.items.isEmpty() -> EmptyState(
                icon = Icons.Filled.Refresh,
                title = "لا توجد تحديثات حالياً",
                subtitle = state.error ?: "جرّب التحديث لاحقاً",
                modifier = Modifier.fillMaxSize()
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(state.items, key = { it.chapterUrl }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenManga(item.source.id, item.mangaSlug) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MangaCover(
                                url = item.coverUrl,
                                contentDescription = item.mangaTitle,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .width(60.dp)
                                    .height(84.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.mangaTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MangaColors.OnSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "الفصل ${if (item.chapterNumber == item.chapterNumber.toInt().toFloat()) item.chapterNumber.toInt() else item.chapterNumber}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MangaColors.PrimaryLight
                                )
                                if (item.timeAgo.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.timeAgo,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MangaColors.Muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
