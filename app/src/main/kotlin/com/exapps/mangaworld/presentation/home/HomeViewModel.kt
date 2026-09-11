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
    val availableSources: List<MangaSource> = MangaSource.entries.toList(),
    val activeSource: MangaSource = MangaSource.AZORA,
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
    private val homeCacheDao: com.exapps.mangaworld.core.data.local.dao.HomeCacheDao
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** First settings emission restores the last source; later ones keep the live choice. */
    private var restoredLastSource = false

    init {
        viewModelScope.launch {
            settingsRepo.getAppSettings()
                .map { settings -> settings to MangaSource.entries.filter { it.id in settings.enabledSources } }
                .distinctUntilChanged()
                .collectLatest { (settings, enabledSources) ->
                    val current = _state.value.activeSource
                    _state.update { it.copy(availableSources = enabledSources) }
                    val lastOpened = MangaSource.fromIdOrNull(settings.lastSourceId)
                    val nextSource = when {
                        enabledSources.isEmpty() -> null
                        !restoredLastSource -> {
                            restoredLastSource = true
                            lastOpened?.takeIf { it in enabledSources }
                                ?: if (current in enabledSources) current else enabledSources.first()
                        }
                        current in enabledSources -> current
                        // Item 10: restore the last opened source instead of
                        // always falling back to the first enabled source.
                        lastOpened != null && lastOpened in enabledSources -> lastOpened
                        else -> enabledSources.first()
                    }
                    if (nextSource == null) {
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
            val mangaId = "${item.source.id}_${item.mangaSlug}"
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

    fun loadHome(source: MangaSource = _state.value.activeSource, blockedKeywords: Set<String> = emptySet()) {
        viewModelScope.launch {
            if (source !in _state.value.availableSources) return@launch
            runCatching { settingsRepo.setLastSourceId(source.id) }
            // Show the cached snapshot instantly (if any) so offline launches
            // still render content; the network refresh replaces it below.
            val cached = runCatching { homeCacheDao.get(source.id) }.getOrNull()
            val cachedData = cached?.payloadJson?.let { HomeCacheCodec.decode(it) }
            if (cachedData != null && _state.value.featured.isEmpty()) {
                applyHomeData(source, cachedData, blockedKeywords, offline = true)
            }
            // Silent refresh when content is already visible (no shimmer flash).
            if (_state.value.featured.isEmpty()) {
                _state.update { it.copy(isLoading = true, error = null) }
            } else {
                _state.update { it.copy(error = null) }
            }
            repo.getHomeData(source)
                .onSuccess { data ->
                    firebaseTelemetry.setActiveSource(source.id)
                    runCatching {
                        homeCacheDao.upsert(
                            HomeCacheEntity(
                                sourceId = source.id,
                                payloadJson = HomeCacheCodec.encode(data)
                            )
                        )
                    }
                    applyHomeData(source, data, blockedKeywords, offline = false)
                    analyticsManager.logHomeLayoutExposure(_state.value.homeLayoutVariant, source.id)
                }
                .onFailure {
                    if (_state.value.featured.isEmpty() && cachedData == null) {
                        _state.update { it.copy(isLoading = false, isOffline = false, error = context.getString(R.string.download_error)) }
                    } else {
                        // Keep the cached content visible with an offline notice.
                        _state.update { it.copy(isLoading = false, isOffline = true, error = context.getString(R.string.home_offline_cached)) }
                    }
                }
        }
    }

    /** Shared filter + state write for fresh and cached payloads. */
    private suspend fun applyHomeData(
        source: MangaSource,
        data: HomeData,
        blockedKeywords: Set<String>,
        offline: Boolean
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
    fun selectSource(source: MangaSource) {
        if (source in _state.value.availableSources) {
            viewModelScope.launch {
                loadHome(source, settingsRepo.getAppSettings().first().contentBlacklist)
            }
        }
    }
}
