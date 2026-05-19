package com.exapps.mangaworld.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CacheManager
import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val cacheManager: CacheManager,
    private val widgetDataRepository: WidgetDataRepository,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {
    val appSettings: StateFlow<AppSettings> = repo.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val readerSettings: StateFlow<ReaderSettings> = repo.getReaderSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())
    private val _imageCacheSizeBytes = MutableStateFlow(0L)
    val imageCacheSizeBytes: StateFlow<Long> = _imageCacheSizeBytes.asStateFlow()

    init { refreshCacheStats() }

    fun setTheme(theme: AppTheme) = viewModelScope.launch { repo.updateTheme(theme) }
    fun setDynamicColors(v: Boolean) = viewModelScope.launch { repo.setDynamicColors(v) }
    fun setBiometricLock(v: Boolean) = viewModelScope.launch { repo.setBiometricLock(v) }
    fun setSecureReader(v: Boolean) = viewModelScope.launch { repo.setSecureReader(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { repo.setDownloadOnWifiOnly(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { repo.setNotificationsEnabled(v) }
    fun setAutoCleanup(v: Boolean) = viewModelScope.launch { repo.setAutoCleanupReadDownloads(v) }
    fun setCleanupHours(v: Int) = viewModelScope.launch { repo.setCleanupAfterHours(v) }
    fun setImageCacheLimit(limitMb: Int) = viewModelScope.launch { repo.setImageCacheLimitMb(limitMb) }
    fun setContentBlacklist(values: Set<String>) = viewModelScope.launch { repo.setContentBlacklist(values) }
    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.toggleSource(id, enabled)
        runCatching { widgetDataRepository.refreshRemoteSnapshot() }
        widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
    }
    fun setReaderMode(m: ReaderMode) = viewModelScope.launch { repo.updateReaderMode(m) }
    fun setBrightness(v: Float) = viewModelScope.launch { repo.updateBrightness(v) }
    fun setKeepScreen(v: Boolean) = viewModelScope.launch { repo.updateKeepScreenOn(v) }
    fun setAutoWebtoon(v: Boolean) = viewModelScope.launch { repo.updateAutoWebtoon(v) }
    fun setIncognito(v: Boolean) = viewModelScope.launch { repo.updateIncognitoMode(v) }
    fun setSmartPrefetch(v: Boolean) = viewModelScope.launch { repo.updateSmartPrefetch(v) }
    fun setReaderHaptics(v: Boolean) = viewModelScope.launch { repo.updateReaderHaptics(v) }
    fun setImageFilter(v: ReaderImageFilter) = viewModelScope.launch { repo.updateImageFilter(v) }
    fun saveCookies(domain: String, cookies: String) = viewModelScope.launch { repo.saveCookies(domain, cookies) }

    fun refreshCacheStats() = viewModelScope.launch {
        _imageCacheSizeBytes.value = cacheManager.getImageCacheSizeBytes()
    }

    fun clearImageCache() = viewModelScope.launch {
        cacheManager.clearImageCache()
        refreshCacheStats()
    }
}
