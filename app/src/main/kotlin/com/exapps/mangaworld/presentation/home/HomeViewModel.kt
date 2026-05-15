package com.exapps.mangaworld.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val featured: List<MangaItem> = emptyList(),
    val latestChapters: List<LatestChapterItem> = emptyList(),
    val trending: List<MangaItem> = emptyList(),
    val activeSource: MangaSource = MangaSource.AZORA,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MangaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { loadHome() }

    fun loadHome(source: MangaSource = MangaSource.AZORA) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repo.getHomeData(source)
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            featured = data.featured,
                            latestChapters = data.latestChapters,
                            trending = data.trending,
                            activeSource = source
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun refresh() = loadHome(_state.value.activeSource)
    fun selectSource(source: MangaSource) = loadHome(source)
}
