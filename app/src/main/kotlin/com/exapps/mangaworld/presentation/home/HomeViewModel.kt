package com.exapps.mangaworld.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.isBlockedBy
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
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
    val suggested: List<MangaItem> = emptyList(),
    val availableSources: List<MangaSource> = MangaSource.entries.toList(),
    val activeSource: MangaSource = MangaSource.AZORA,
    val remoteAlertMessage: String = "",
    val homeLayoutVariant: String = "default",
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MangaRepository,
    private val settingsRepo: SettingsRepository,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val firebaseTelemetry: FirebaseTelemetry
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.getAppSettings()
                .map { settings -> settings to MangaSource.entries.filter { it.id in settings.enabledSources } }
                .distinctUntilChanged()
                .collectLatest { (settings, enabledSources) ->
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
                        loadHome(nextSource, settings.contentBlacklist)
                    }
                }
        }
        viewModelScope.launch {
            remoteConfigManager.remoteAlertMessage.collect { message ->
                _state.update { it.copy(remoteAlertMessage = message) }
            }
        }
        viewModelScope.launch {
            remoteConfigManager.homeLayoutVariant.collect { variant ->
                _state.update { it.copy(homeLayoutVariant = variant) }
            }
        }
    }

    fun loadHome(source: MangaSource = _state.value.activeSource, blockedKeywords: Set<String> = emptySet()) {
        viewModelScope.launch {
            if (source !in _state.value.availableSources) return@launch
            _state.update { it.copy(isLoading = true, error = null) }
            repo.getHomeData(source)
                .onSuccess { data ->
                    firebaseTelemetry.setActiveSource(source.id)
                    val filteredFeatured = data.featured.filterNot { it.isBlockedBy(blockedKeywords) }
                    val filteredLatest = data.latestChapters.filterNot { it.isBlockedBy(blockedKeywords) }
                    val filteredTrending = data.trending.filterNot { it.isBlockedBy(blockedKeywords) }
                    val suggested = repo.getSuggestedManga(filteredFeatured + filteredTrending)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            featured = filteredFeatured,
                            latestChapters = filteredLatest,
                            trending = filteredTrending,
                            suggested = suggested,
                            activeSource = source
                        )
                    }
                    analyticsManager.logHomeLayoutExposure(_state.value.homeLayoutVariant, source.id)
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun refresh() = viewModelScope.launch {
        val blacklist = settingsRepo.getAppSettings().first().contentBlacklist
        loadHome(_state.value.activeSource, blacklist)
    }
    fun selectSource(source: MangaSource) {
        if (source in _state.value.availableSources) {
            viewModelScope.launch {
                loadHome(source, settingsRepo.getAppSettings().first().contentBlacklist)
            }
        }
    }
}
