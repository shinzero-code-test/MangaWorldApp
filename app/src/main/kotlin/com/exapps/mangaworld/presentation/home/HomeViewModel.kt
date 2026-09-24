package com.exapps.mangaworld.presentation.home

import android.content.Context
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.isBlockedBy
import com.exapps.mangaworld.core.data.local.HomeCacheCodec
import com.exapps.mangaworld.core.data.local.entity.HomeCacheEntity
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.source.plugins.SourceUiEntry
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val featured: List<MangaItem> = emptyList(),
    val latestChapters: List<LatestChapterItem> = emptyList(),
    val trending: List<MangaItem> = emptyList(),
    val suggested: List<MangaItem> = emptyList(),
    val availableSources: List<SourceUiEntry> = emptyList(),
    val activeSource: SourceId = SourceId("azora"),
    /** True when showing a cached snapshot because the network failed (item 9). */
    val isOffline: Boolean = false,
    /** Library membership by mangaId — drives the bookmark state on chapter cards (#12). */
    val favoriteIds: Set<String> = emptySet(),
    val remoteAlertMessage: String = "",
    val homeLayoutVariant: String = "default",
    /** Header: Firebase photo URL preferred, community avatar wins when set. */
    val avatarUrl: String? = null,
    val avatarInitial: String = "",
    /** Header: unread community notifications badge. */
    val unreadNotifications: Int = 0,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val repo: MangaRepository,
    private val settingsRepo: SettingsRepository,
    private val libraryRepo: com.exapps.mangaworld.domain.repository.LibraryRepository,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val firebaseTelemetry: FirebaseTelemetry,
    private val sessionManager: com.exapps.mangaworld.core.firebase.FirebaseSessionManager,
    private val communityRepo: com.exapps.mangaworld.domain.repository.CommunityRepository,
    private val homeCacheDao: com.exapps.mangaworld.core.data.local.dao.HomeCacheDao,
    private val sourceUiMapper: SourceUiMapper
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** First settings emission restores the last source; later ones keep the live choice. */
    private var restoredLastSource = false

    /**
     * Source-load generation: every loadHome cancels the previous job and
     * bumps the sequence, so a slow earlier source that lands last can never
     * overwrite the newer selection (stale completions are dropped even if
     * cancellation lands late).
     */
    private var homeLoadJob: Job? = null
    private var homeLoadSeq = 0L

    init {
        viewModelScope.launch {
            settingsRepo.getAppSettings()
                .map { settings ->
                    val entries = sourceUiMapper.entries().filter { it.id in settings.enabledSources }
                    settings to entries
                }
                // Key on content-affecting settings ONLY: loadHome persists
                // lastSourceId on every load, and reacting to our own write
                // re-fired a load of the stale activeSource that raced (and
                // could beat) the user's tap.
                .distinctUntilChanged { old, new ->
                    old.first.enabledSources == new.first.enabledSources &&
                        old.first.contentBlacklist == new.first.contentBlacklist
                }
                .collectLatest { (settings, enabledSources) ->
                    val current = _state.value.activeSource
                    _state.update { it.copy(availableSources = enabledSources) }
                    val enabledIds = enabledSources.map { it.id }.toSet()
                    val lastOpened = settings.lastSourceId.takeIf {
                        BuiltinSourceIds.isBuiltin(it) && it in enabledIds
                    }?.let { SourceId(it) }
                    val currentKnown = current.takeIf { it.value in enabledIds }
                    val nextSource = when {
                        enabledSources.isEmpty() -> null
                        !restoredLastSource -> {
                            restoredLastSource = true
                            lastOpened ?: currentKnown ?: SourceId(enabledSources.first().id)
                        }
                        currentKnown != null -> currentKnown
                        // Item 10: restore the last opened source instead of
                        // always falling back to the first enabled source.
                        lastOpened != null -> lastOpened
                        else -> SourceId(enabledSources.first().id)
                    }
                    if (nextSource == null) {
                        homeLoadJob?.cancel()
                        homeLoadSeq++
                        _state.update {
                            it.copy(
                                isLoading = false,
                                featured = emptyList(),
                                latestChapters = emptyList(),
                                trending = emptyList(),
                                error = context.getString(R.string.str_341)
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
        // v8 (#12): observe library so the card bookmark buttons reflect real state.
        viewModelScope.launch {
            libraryRepo.getFavorites().collect { favorites ->
                _state.update { it.copy(favoriteIds = favorites.mapTo(mutableSetOf()) { f -> f.mangaId }) }
            }
        }
        // Header identity: Firebase photo, upgraded to the community avatar
        // when the user set one. Guests fall back to an initial letter.
        viewModelScope.launch {
            sessionManager.authState.collectLatest { user ->
                val fallbackUrl = user?.photoUrl?.toString()
                val fallbackInitial = initialOf(
                    user?.displayName?.takeIf { it.isNotBlank() }
                        ?: user?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
                )
                _state.update { it.copy(avatarUrl = fallbackUrl, avatarInitial = fallbackInitial) }
                val uid = user?.uid
                if (uid != null) {
                    runCatching {
                        communityRepo.observePublicProfile(uid).collect { profile ->
                            val avt = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl
                            _state.update {
                                it.copy(
                                    avatarUrl = avt,
                                    avatarInitial = initialOf(
                                        profile?.displayName?.takeIf { it.isNotBlank() }
                                            ?: profile?.username?.takeIf { it.isNotBlank() }
                                    ).ifBlank { fallbackInitial }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Header badge: unread community notifications.
        viewModelScope.launch {
            communityRepo.observeNotifications(50)
                .map { list -> list.count { !it.read } }
                .catch { emit(0) }
                .collect { count -> _state.update { s -> s.copy(unreadNotifications = count) } }
        }
    }

    private fun initialOf(name: String?): String {
        val first = name?.trim()?.firstOrNull()?.toString()?.uppercase().orEmpty()
        return first.ifBlank { context.getString(R.string.guest).trim().take(1).ifBlank { "?" } }
    }

    /**
     * Toggle favourite from a latest-chapter card (#12). Builds a minimal
     * [FavoriteManga] from the card payload; the detail screen enriches it on
     * first open via ensureLibraryEntry.
     */
    fun toggleFavorite(item: LatestChapterItem) {
        viewModelScope.launch {
            val mangaId = "${item.source.value}_${item.mangaSlug}"
            if (mangaId in _state.value.favoriteIds) {
                libraryRepo.removeFavorite(mangaId)
            } else {
                libraryRepo.ensureLibraryEntry(
                    FavoriteManga(
                        mangaId = mangaId,
                        slug = item.mangaSlug,
                        title = item.mangaTitle,
                        coverUrl = item.coverUrl,
                        source = item.source
                    )
                )
            }
        }
    }

    fun loadHome(source: SourceId = _state.value.activeSource, blockedKeywords: Set<String> = emptySet()) {
        if (_state.value.availableSources.none { it.id == source.value }) return
        // New generation first: any in-flight older load is obsolete, and its
        // late response must be ignored even if cancellation lands late.
        homeLoadJob?.cancel()
        val seq = ++homeLoadSeq
        if (source != _state.value.activeSource) {
            // Optimistic switch: the highlight moves instantly and the old
            // source's rows never render as the new source's (the previous
            // silent-refresh kept showing them until the network returned,
            // and on failure the highlight never moved at all).
            _state.update {
                it.copy(
                    activeSource = source,
                    isLoading = true,
                    isOffline = false,
                    error = null,
                    featured = emptyList(),
                    latestChapters = emptyList(),
                    trending = emptyList(),
                    suggested = emptyList()
                )
            }
        }
        homeLoadJob = viewModelScope.launch {
            runCatching { settingsRepo.setLastSourceId(source.value) }
            // Show the cached snapshot instantly (if any) so offline launches
            // still render content; the network refresh replaces it below.
            val cached = runCatching { homeCacheDao.get(source.value) }.getOrNull()
            val cachedData = cached?.payloadJson?.let { HomeCacheCodec.decode(it) }
            if (cachedData != null && _state.value.featured.isEmpty()) {
                if (seq == homeLoadSeq) applyHomeData(source, cachedData, blockedKeywords, offline = true, seq = seq)
            }
            if (seq != homeLoadSeq) return@launch
            // Silent refresh when content is already visible (no shimmer flash).
            if (_state.value.featured.isEmpty()) {
                _state.update { it.copy(isLoading = true, error = null) }
            } else {
                _state.update { it.copy(error = null) }
            }
            repo.getHomeData(source)
                .onSuccess { data ->
                    if (seq != homeLoadSeq) return@onSuccess
                    firebaseTelemetry.setActiveSource(source.value)
                    runCatching {
                        homeCacheDao.upsert(
                            HomeCacheEntity(
                                sourceId = source.value,
                                payloadJson = HomeCacheCodec.encode(data)
                            )
                        )
                    }
                    applyHomeData(source, data, blockedKeywords, offline = false, seq = seq)
                    analyticsManager.logHomeLayoutExposure(_state.value.homeLayoutVariant, source.value)
                }
                .onFailure {
                    if (seq != homeLoadSeq) return@onFailure
                    if (_state.value.featured.isEmpty() && cachedData == null) {
                        _state.update { it.copy(isLoading = false, isOffline = false, error = context.getString(R.string.download_error)) }
                    } else {
                        // Keep the cached content visible with an offline notice.
                        _state.update { it.copy(isLoading = false, isOffline = true, error = context.getString(R.string.home_offline_cached)) }
                    }
                }
        }
    }

    /** Shared filter + state write for fresh and cached payloads. Drops stale generations. */
    private suspend fun applyHomeData(
        source: SourceId,
        data: HomeData,
        blockedKeywords: Set<String>,
        offline: Boolean,
        seq: Long
    ) {
        val filteredFeatured = data.featured.filterNot { it.isBlockedBy(blockedKeywords) }.distinctBy { it.id }
        val filteredLatest = data.latestChapters.filterNot { it.isBlockedBy(blockedKeywords) }.distinctBy { it.chapterUrl }
        val filteredTrending = data.trending.filterNot { it.isBlockedBy(blockedKeywords) }.distinctBy { it.id }
        // Candidates must be id-distinct BEFORE scoring: a manga that
        // appears in both featured and trending otherwise comes back
        // twice and crashes the LazyRow with duplicate keys.
        val suggested = if (offline) {
            emptyList()
        } else {
            repo.getSuggestedManga(
                (filteredFeatured + filteredTrending).distinctBy { it.id }
            ).distinctBy { it.id }
        }
        // A newer source switch started while suggestions were scoring —
        // never paint this generation's rows over it.
        if (seq != homeLoadSeq) return
        _state.update {
            it.copy(
                isLoading = false,
                isOffline = offline,
                error = if (offline) context.getString(R.string.home_offline_cached) else null,
                featured = filteredFeatured,
                latestChapters = filteredLatest,
                trending = filteredTrending,
                suggested = suggested,
                activeSource = source
            )
        }
    }

    fun refresh() = viewModelScope.launch {
        val blacklist = settingsRepo.getAppSettings().first().contentBlacklist
        loadHome(_state.value.activeSource, blacklist)
    }
    fun selectSource(sourceId: String) {
        val source = SourceId(sourceId)
        if (_state.value.availableSources.any { it.id == sourceId }) {
            viewModelScope.launch {
                loadHome(source, settingsRepo.getAppSettings().first().contentBlacklist)
            }
        }
    }
}
