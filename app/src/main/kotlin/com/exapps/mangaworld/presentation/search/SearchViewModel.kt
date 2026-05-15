package com.exapps.mangaworld.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repo: MangaRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _source = MutableStateFlow<MangaSource?>(null)

    val query: StateFlow<String> = _query.asStateFlow()
    val source: StateFlow<MangaSource?> = _source.asStateFlow()

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
        combine(_query, _source) { q, s -> q to s }
            .debounce(400)
            .filter { (q, _) -> q.length >= 2 }
            .flatMapLatest { (q, s) ->
                repo.searchManga(SearchFilters(query = q, source = s))
            }
            .cachedIn(viewModelScope)

    fun setQuery(q: String) = _query.update { q }
    fun setSource(source: MangaSource?) = _source.update { source }
    fun clear() = _query.update { "" }

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
