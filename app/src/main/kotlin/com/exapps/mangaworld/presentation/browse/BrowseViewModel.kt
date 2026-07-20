package com.exapps.mangaworld.presentation.browse

import android.content.Context
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import androidx.compose.runtime.Stable
import javax.inject.Inject

@Stable
data class BrowseUiState(
    val query: String = "",
    val selectedGenre: String? = null,
    val selectedStatus: MangaStatus? = null,
    val selectedType: MangaType? = null,
    val selectedSource: MangaSource? = null,
    val sortBy: SortBy = SortBy.LATEST,
    val isGridView: Boolean = true,
    val enabledSourceIds: Set<String> = MangaSource.entries.map { it.id }.toSet(),
    val blockedKeywords: Set<String> = emptySet(),
    val genres: List<String> = emptyList()
) {
    val filters get() = SearchFilters(
        query = query,
        genre = selectedGenre,
        status = selectedStatus,
        type = selectedType,
        source = selectedSource,
        sortBy = sortBy,
        enabledSourceIds = enabledSourceIds,
        blockedKeywords = blockedKeywords
    )
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val repo: MangaRepository,
    settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    val mangaFlow: Flow<PagingData<MangaItem>> = _uiState
        .debounce(400)
        .flatMapLatest { repo.searchManga(it.filters) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            combine(settingsRepo.getAppSettings(), _uiState) { settings, ui -> Triple(settings.enabledSources, settings.contentBlacklist, ui.selectedSource) }
                .distinctUntilChanged()
                .collectLatest { (enabledSources, blacklist, selectedSource) ->
                    val loaded = repo.getGenres(source = selectedSource, enabledSourceIds = enabledSources)
                    val availableGenres = listOf(context.getString(R.string.browse_all)) + loaded
                    _uiState.update {
                        it.copy(
                            enabledSourceIds = enabledSources,
                            blockedKeywords = blacklist,
                            selectedSource = it.selectedSource?.takeIf { src -> src.id in enabledSources },
                            selectedGenre = it.selectedGenre?.takeIf { genre -> genre in loaded },
                            genres = availableGenres
                        )
                    }
                }
        }
    }

    fun setQuery(q: String) = _uiState.update { it.copy(query = q) }
    fun setGenre(g: String?) = _uiState.update { it.copy(selectedGenre = g) }
    fun setStatus(s: MangaStatus?) = _uiState.update { it.copy(selectedStatus = s) }
    fun setType(t: MangaType?) = _uiState.update { it.copy(selectedType = t) }
    fun setSource(src: MangaSource?) = _uiState.update { it.copy(selectedSource = src) }
    fun setSortBy(sort: SortBy) = _uiState.update { it.copy(sortBy = sort) }
    fun toggleView() = _uiState.update { it.copy(isGridView = !it.isGridView) }
}
