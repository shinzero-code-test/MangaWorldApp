package com.exapps.mangaworld.presentation.sources

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceBrowseUiState(
    val source: MangaSource = MangaSource.AZORA,
    val query: String = "",
    val mangaList: List<MangaItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorText: String? = null,
    val needsCloudflare: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val selectedGenre: String? = null,
    val selectedStatus: MangaStatus? = null,
    val selectedType: MangaType? = null,
    val sortBy: SortBy = SortBy.LATEST
)

@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    private val mangaRepository: MangaRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sourceId: String = savedStateHandle["sourceId"] ?: "azora"

    private val _uiState = MutableStateFlow(SourceBrowseUiState(source = MangaSource.fromId(sourceId)))
    val uiState: StateFlow<SourceBrowseUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query, currentPage = 1, mangaList = emptyList(), hasMore = true)
        loadData()
    }

    fun setStatus(status: MangaStatus?) {
        _uiState.value = _uiState.value.copy(selectedStatus = status, currentPage = 1, mangaList = emptyList(), hasMore = true)
        loadData()
    }

    fun setType(type: MangaType?) {
        _uiState.value = _uiState.value.copy(selectedType = type, currentPage = 1, mangaList = emptyList(), hasMore = true)
        loadData()
    }

    fun setSortBy(sort: SortBy) {
        _uiState.value = _uiState.value.copy(sortBy = sort, currentPage = 1, mangaList = emptyList(), hasMore = true)
        loadData()
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.isLoading || !s.hasMore) return
        _uiState.value = s.copy(currentPage = s.currentPage + 1)
        loadData()
    }

    fun dismissCloudflare() {
        _uiState.value = _uiState.value.copy(needsCloudflare = false, errorText = null)
        // Retry after CF solve
        _uiState.value = _uiState.value.copy(currentPage = 1, mangaList = emptyList(), hasMore = true)
        loadData()
    }

    private fun loadData() {
        val s = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorText = null)
            try {
                val result = if (s.query.isNotBlank()) {
                    mangaRepository.searchMangaDirect(s.query, s.source, s.currentPage)
                } else {
                    mangaRepository.browseMangaDirect(
                        s.source, s.currentPage,
                        genre = s.selectedGenre,
                        status = s.selectedStatus,
                        type = s.selectedType,
                        sortBy = s.sortBy
                    )
                }
                result.onSuccess { items ->
                    val existing = if (s.currentPage > 1) s.mangaList else emptyList()
                    _uiState.value = _uiState.value.copy(
                        mangaList = existing + items,
                        hasMore = items.isNotEmpty(),
                        isLoading = false
                    )
                }.onFailure { e ->
                    handleFailure(e)
                }
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    private fun handleFailure(e: Exception) {
        when {
            e is CloudflareChallengeException -> {
                _uiState.value = _uiState.value.copy(needsCloudflare = true, isLoading = false)
            }
            e.message?.contains("Cloudflare", true) == true ||
            e.message?.contains("403", true) == true -> {
                _uiState.value = _uiState.value.copy(needsCloudflare = true, isLoading = false)
            }
            else -> {
                _uiState.value = _uiState.value.copy(errorText = e.message ?: "خطأ غير معروف", isLoading = false)
            }
        }
    }
}
