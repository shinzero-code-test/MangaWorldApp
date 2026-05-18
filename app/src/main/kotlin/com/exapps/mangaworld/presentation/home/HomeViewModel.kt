package com.exapps.mangaworld.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val featured: List<MangaItem> = emptyList(),
    val latestChapters: List<LatestChapterItem> = emptyList(),
    val trending: List<MangaItem> = emptyList(),
    val availableSources: List<MangaSource> = MangaSource.entries.toList(),
    val activeSource: MangaSource = MangaSource.AZORA,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MangaRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.getAppSettings()
                .map { settings -> MangaSource.entries.filter { it.id in settings.enabledSources } }
                .distinctUntilChanged()
                .collectLatest { enabledSources ->
                    val current = _state.value.activeSource
                    _state.update { it.copy(availableSources = enabledSources) }
                    val nextSource = when {
                        enabledSources.isEmpty() -> null
                        current in enabledSources -> current
                        else -> enabledSources.first()
                    }
                    if (nextSource == null) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                featured = emptyList(),
                                latestChapters = emptyList(),
                                trending = emptyList(),
                                error = "فعّل مصدراً واحداً على الأقل من الإعدادات"
                            )
                        }
                    } else {
                        loadHome(nextSource)
                    }
                }
        }
    }

    fun loadHome(source: MangaSource = _state.value.activeSource) {
        viewModelScope.launch {
            if (source !in _state.value.availableSources) return@launch
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
    fun selectSource(source: MangaSource) {
        if (source in _state.value.availableSources) loadHome(source)
    }
}
