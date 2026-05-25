package com.exapps.mangaworld.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: MangaRepository,
    private val settingsRepo: SettingsRepository,
    private val analyticsManager: FirebaseAnalyticsManager
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _source = MutableStateFlow<MangaSource?>(null)
    private val _reloadToken = MutableStateFlow(0)

    private val appSettings: StateFlow<AppSettings> = settingsRepo.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val query: StateFlow<String> = _query.asStateFlow()
    val source: StateFlow<MangaSource?> = _source.asStateFlow()
    val enabledSources: StateFlow<List<MangaSource>> = appSettings
        .map { settings -> MangaSource.entries.filter { it.id in settings.enabledSources } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MangaSource.entries.toList())

    init {
        viewModelScope.launch {
            enabledSources.collect { enabled ->
                if (_source.value != null && _source.value !in enabled) {
                    _source.value = null
                }
            }
        }

        viewModelScope.launch {
            combine(_query.debounce(700), _source, enabledSources) { query, source, enabled ->
                Triple(query.trim(), source, enabled)
            }
                .filter { (query, _, _) -> query.length >= 2 }
                .distinctUntilChanged()
                .collect { (query, source, enabled) ->
                    analyticsManager.logSearchQuery(
                        query = query,
                        sourceId = source?.id,
                        enabledSources = enabled.size
                    )
                }
        }
    }

    /**
     * True when the currently selected source requires Cloudflare verification.
     * Shown as a warning banner in the UI.
     */
    val selectedSourceRequiresVerification: StateFlow<Boolean> =
        _source.map { it?.requiresVerification == true }.stateIn(
            viewModelScope, SharingStarted.Eagerly, false
        )

    /**
     * Paging results — triggers on any meaningful change of (query ≥ 2 chars, source).
     * Source-only changes also trigger a new search.
     */
    val results: Flow<PagingData<MangaItem>> =
        combine(_query, _source, enabledSources, appSettings, _reloadToken) { q, s, enabled, settings, reload ->
            SearchRequest(q, s, enabled, settings, reload)
        }
            .debounce(400)
            .filter { it.query.length >= 2 }
            .flatMapLatest { request ->
                val selectedSource = request.source?.takeIf { it in request.enabledSources }
                repo.searchManga(
                    SearchFilters(
                        query = request.query,
                        source = selectedSource,
                        enabledSourceIds = request.enabledSources.map { it.id }.toSet(),
                        blockedKeywords = request.appSettings.contentBlacklist
                    )
                )
            }
            .cachedIn(viewModelScope)

    fun setQuery(q: String) = _query.update { q }
    fun setSource(source: MangaSource?) = _source.update { source }
    fun clear() = _query.update { "" }
    fun reload() = _reloadToken.update { it + 1 }
    fun saveCookies(domain: String, cookies: String) = viewModelScope.launch {
        settingsRepo.saveCookies(domain, cookies)
    }

    /**
     * Convenience check: tells callers if results are expected to be empty because all
     * active sources are behind Cloudflare and no verification cookie exists.
     */
    fun shouldShowCloudflareBanner(): Boolean =
        _source.value?.requiresVerification == true

    companion object {
        fun isCloudflareCause(t: Throwable) = t is CloudflareChallengeException
    }
}

private data class SearchRequest(
    val query: String,
    val source: MangaSource?,
    val enabledSources: List<MangaSource>,
    val appSettings: AppSettings,
    val reloadToken: Int
)
