package com.exapps.mangaworld.presentation.sources

import android.content.Context
import android.os.SystemClock
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceBrowseUiState(
    val source: com.exapps.mangaworld.core.source.plugins.SourceId = com.exapps.mangaworld.core.source.plugins.SourceId("azora"),
    val sourceEntry: com.exapps.mangaworld.core.source.plugins.SourceUiEntry? = null,
    /** Effective solver target (follows domain overrides; empty when unknown). */
    val solverBaseUrl: String = "",
    val solverHost: String = "",
    val query: String = "",
    val mangaList: List<MangaItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorText: String? = null,
    val needsCloudflare: Boolean = false,
    val cfAutoTriggerDisabled: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val selectedGenre: String? = null,
    val selectedStatus: MangaStatus? = null,
    val selectedType: MangaType? = null,
    val sortBy: SortBy = SortBy.LATEST
)

@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
    private val sourceUiMapper: com.exapps.mangaworld.core.source.plugins.SourceUiMapper,
    private val sourceRegistry: com.exapps.mangaworld.core.source.plugins.SourceRegistry
) : ViewModel() {

    private val sourceId: String = savedStateHandle["sourceId"] ?: "azora"

    private val _uiState = MutableStateFlow(
        SourceBrowseUiState(
            source = com.exapps.mangaworld.core.source.plugins.SourceId(sourceId),
            sourceEntry = sourceUiMapper.entry(sourceId),
            solverBaseUrl = solverTarget().first,
            solverHost = solverTarget().second
        )
    )
    val uiState: StateFlow<SourceBrowseUiState> = _uiState.asStateFlow()

    /**
     * First successful load marker. The constructor already seeds
     * `sourceEntry`, so "row unchanged" cannot also mean "already loaded" —
     * without this the initial emission looked identical and nothing ever
     * loaded. Reloads still happen when the row itself changes (re-verify,
     * status flip) or when the source becomes servable after a hold.
     */
    private var hasLoadedOnce = false

    init {
        // v9.2.3: the pinned-launcher-shortcut path (and any deep link) can
        // land BEFORE boot resume finished registering an installed source.
        // Reading the row once in the constructor froze "unknown source" in the
        // title and fired the first load against an unregistered id, so the
        // shortcut opened to an empty screen and stayed that way. Follow the
        // live row stream and the live enablement setting instead.
        //
        // Enablement gate: this screen is reachable from OUTSIDE the app
        // (launcher pin, exported deep link) and used to drive live traffic
        // straight into the scraper, bypassing the `enabledSources` check every
        // in-app list screen applies — so a source the user disabled (or never
        // approved) still fetched and rendered through its shortcut.
        viewModelScope.launch {
            combine(
                sourceUiMapper.entryFlow(sourceId),
                settingsRepository.getAppSettings()
            ) { entry, settings -> entry to settings.enabledSources }
                .distinctUntilChanged()
                .collect { (entry, enabled) ->
                    val serving = entry != null &&
                        sourceId in enabled &&
                        entry.rowState == PluginRowState.SERVING
                    val previous = _uiState.value.sourceEntry
                    val (base, host) = solverTarget()
                    _uiState.value = _uiState.value.copy(
                        sourceEntry = entry,
                        solverBaseUrl = base,
                        solverHost = host,
                        // Known but not serving (disabled / awaiting approval /
                        // quarantined): say why instead of fetching anyway.
                        // Unknown stays untouched — the grace watcher decides.
                        errorText = when {
                            serving -> null
                            entry != null -> context.getString(R.string.plugin_source_not_serving)
                            else -> _uiState.value.errorText
                        }
                    )
                    if (serving && (!hasLoadedOnce || entry != previous)) {
                        hasLoadedOnce = true
                        loadData()
                    }
                }
        }
        // Fail closed (v9.2.3): an id the registry never claims — dead or
        // removed source, or a pinned shortcut whose plugin was uninstalled —
        // must say so instead of rendering an empty list that reads like a
        // network failure. The grace window covers boot resume racing this
        // screen; a source that registers later still drives the collector.
        viewModelScope.launch {
            val deadline = SystemClock.uptimeMillis() + REGISTRATION_GRACE_MS
            while (!sourceRegistry.isKnown(sourceId) && SystemClock.uptimeMillis() < deadline) {
                delay(REGISTRATION_POLL_MS)
            }
            if (!sourceRegistry.isKnown(sourceId) && _uiState.value.sourceEntry == null &&
                _uiState.value.errorText == null
            ) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorText = context.getString(R.string.source_unknown)
                )
            }
        }
    }

    private fun solverTarget(): Pair<String, String> {
        val descriptor = sourceRegistry.descriptorFor(sourceId) ?: return "" to ""
        val base = com.exapps.mangaworld.domain.model.SourceDomainOverrides.baseUrlFor(sourceId, descriptor.baseUrl)
        return base to com.exapps.mangaworld.core.source.plugins.HostPolicy.hostOf(base)
    }

    private companion object {
        /** Boot resume budget to wait for registration before declaring the id dead. */
        const val REGISTRATION_GRACE_MS = 8_000L
        const val REGISTRATION_POLL_MS = 200L
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

    fun onCloudflareSolved(domain: String, cookies: String) {
        // Save cookies to both in-memory cache and persistent storage
        CookieCache.put(domain, cookies)
        viewModelScope.launch {
            settingsRepository.saveCookies(domain, cookies)
        }
        // Disable auto-trigger so we don't loop; show manual banner if it fails again
        _uiState.value = _uiState.value.copy(
            needsCloudflare = false,
            cfAutoTriggerDisabled = true,
            errorText = null
        )
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
                        mangaList = (existing + items).distinctBy { it.id },
                        hasMore = items.isNotEmpty(),
                        isLoading = false
                    )
                }.onFailure { e ->
                    handleFailure(e as? Exception ?: Exception(e.message, e))
                }
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    private fun handleFailure(e: Exception) {
        when {
            e is CloudflareChallengeException -> {
                _uiState.value = _uiState.value.copy(
                    needsCloudflare = true,
                    cfAutoTriggerDisabled = false,
                    isLoading = false
                )
            }
            e.message?.contains("Cloudflare", true) == true ||
            e.message?.contains("403", true) == true -> {
                _uiState.value = _uiState.value.copy(
                    needsCloudflare = true,
                    cfAutoTriggerDisabled = false,
                    isLoading = false
                )
            }
            else -> {
                _uiState.value = _uiState.value.copy(// Generic string: raw backend text must never reach UI state (matches HomeViewModel).
                errorText = context.getString(R.string.download_error), isLoading = false)
            }
        }
    }
}
