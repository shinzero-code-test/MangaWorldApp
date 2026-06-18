package com.exapps.mangaworld.presentation.search

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class AdvancedSearchFilters(
    val query: String = "",
    val source: MangaSource? = null,
    val genre: String? = null,
    val status: MangaStatus? = null,
    val type: MangaType? = null,
    val sortBy: SortBy = SortBy.LATEST,
    val minChapters: Int? = null,
    val maxChapters: Int? = null,
    val minRating: Float? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: MangaRepository,
    private val settingsRepo: SettingsRepository,
    private val analyticsManager: FirebaseAnalyticsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _source = MutableStateFlow<MangaSource?>(null)
    private val _reloadToken = MutableStateFlow(0)
    private val _filters = MutableStateFlow(AdvancedSearchFilters())
    private val _showAdvancedFilters = MutableStateFlow(false)

    private val appSettings: StateFlow<AppSettings> = settingsRepo.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val query: StateFlow<String> = _query.asStateFlow()
    val source: StateFlow<MangaSource?> = _source.asStateFlow()
    val filters: StateFlow<AdvancedSearchFilters> = _filters.asStateFlow()
    val showAdvancedFilters: StateFlow<Boolean> = _showAdvancedFilters.asStateFlow()
    val enabledSources: StateFlow<List<MangaSource>> = appSettings
        .map { settings -> MangaSource.entries.filter { it.id in settings.enabledSources } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MangaSource.entries.toList())

    // Search history
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // Available genres (loaded from sources)
    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres: StateFlow<List<String>> = _availableGenres.asStateFlow()

    init {
        loadSearchHistory()
        loadAvailableGenres()

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

    val selectedSourceRequiresVerification: StateFlow<Boolean> =
        _source.map { it?.requiresVerification == true }.stateIn(
            viewModelScope, SharingStarted.Eagerly, false
        )

    val results: Flow<PagingData<MangaItem>> =
        combine(_query, _source, enabledSources, appSettings, _reloadToken, _filters) { q, s, enabled, settings, reload, advancedFilters ->
            SearchRequest(q, s, enabled, settings, reload, advancedFilters)
        }
            .debounce(400)
            .filter { it.query.length >= 2 || it.advancedFilters.genre != null }
            .flatMapLatest { request ->
                val selectedSource = request.source?.takeIf { it in request.enabledSources }
                val filters = request.advancedFilters
                repo.searchManga(
                    SearchFilters(
                        query = request.query,
                        source = selectedSource,
                        genre = filters.genre,
                        status = filters.status,
                        type = filters.type,
                        sortBy = filters.sortBy,
                        enabledSourceIds = request.enabledSources.map { it.id }.toSet(),
                        blockedKeywords = request.appSettings.contentBlacklist
                    )
                )
            }
            .cachedIn(viewModelScope)

    fun setQuery(q: String) {
        _query.update { q }
        _filters.update { it.copy(query = q) }
    }

    fun setSource(source: MangaSource?) = _source.update { source }

    fun setAdvancedFilter(genre: String? = null, status: MangaStatus? = null, type: MangaType? = null, sortBy: SortBy? = null) {
        _filters.update { current ->
            current.copy(
                genre = genre ?: current.genre,
                status = status ?: current.status,
                type = type ?: current.type,
                sortBy = sortBy ?: current.sortBy
            )
        }
    }

    fun clearAdvancedFilters() {
        _filters.update { AdvancedSearchFilters(query = _query.value) }
    }

    fun toggleAdvancedFilters() {
        _showAdvancedFilters.update { !it }
    }

    fun clear() {
        _query.update { "" }
        _filters.update { AdvancedSearchFilters() }
    }

    fun reload() = _reloadToken.update { it + 1 }

    fun addToHistory(query: String) {
        if (query.isBlank()) return
        val current = _searchHistory.value.toMutableList()
        current.remove(query) // Remove if exists (move to top)
        current.add(0, query)
        if (current.size > 20) current.removeAt(current.lastIndex)
        _searchHistory.value = current
        saveSearchHistory()
    }

    fun removeFromHistory(query: String) {
        _searchHistory.update { it.filter { q -> q != query } }
        saveSearchHistory()
    }

    fun clearHistory() {
        _searchHistory.value = emptyList()
        saveSearchHistory()
    }

    fun saveCookies(domain: String, cookies: String) = viewModelScope.launch {
        settingsRepo.saveCookies(domain, cookies)
    }

    fun shouldShowCloudflareBanner(): Boolean =
        _source.value?.requiresVerification == true

    private fun loadSearchHistory() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
            val historyJson = prefs.getString("search_history", "[]") ?: "[]"
            _searchHistory.value = parseHistoryList(historyJson)
        }
    }

    private fun saveSearchHistory() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("search_history", _searchHistory.value.toString()).apply()
        }
    }

    private fun loadAvailableGenres() {
        viewModelScope.launch {
            try {
                val genres = repo.getGenres()
                _availableGenres.value = genres
            } catch (e: Exception) {
                // Ignore - genres will be loaded later
            }
        }
    }

    private fun parseHistoryList(json: String): List<String> {
        return try {
            val cleaned = json.trim().removePrefix("[").removeSuffix("]")
            if (cleaned.isBlank()) return emptyList()
            cleaned.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun isCloudflareCause(t: Throwable) = t is CloudflareChallengeException
    }
}

private data class SearchRequest(
    val query: String,
    val source: MangaSource?,
    val enabledSources: List<MangaSource>,
    val appSettings: AppSettings,
    val reloadToken: Int,
    val advancedFilters: AdvancedSearchFilters
)
