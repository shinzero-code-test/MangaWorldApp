package com.exapps.mangaworld.presentation.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject

data class BrowseUiState(
    val query: String = "",
    val selectedGenre: String? = null,
    val selectedStatus: MangaStatus? = null,
    val selectedType: MangaType? = null,
    val selectedSource: MangaSource? = null,
    val sortBy: SortBy = SortBy.LATEST,
    val isGridView: Boolean = true,
    val genres: List<String> = listOf("الكل")
) {
    val filters get() = SearchFilters(
        query = query,
        genre = selectedGenre,
        status = selectedStatus,
        type = selectedType,
        source = selectedSource,
        sortBy = sortBy
    )
}

@HiltViewModel
class BrowseViewModel @Inject constructor(private val repo: MangaRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    val mangaFlow: Flow<PagingData<MangaItem>> = _uiState
        .debounce(400)
        .flatMapLatest { repo.searchManga(it.filters) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            val loaded = repo.getGenres()
            _uiState.update { it.copy(genres = listOf("الكل") + loaded) }
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
