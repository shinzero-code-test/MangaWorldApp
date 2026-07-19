import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CacheManager
import com.exapps.mangaworld.core.data.LocalBackupManager
import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
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
    private val localBackupManager: LocalBackupManager,
    private val widgetDataRepository: WidgetDataRepository,
    private val firebaseSyncManager: FirebaseSyncManager,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {
    val appSettings: StateFlow<AppSettings> = repo.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val readerSettings: StateFlow<ReaderSettings> = repo.getReaderSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())
    private val _imageCacheSizeBytes = MutableStateFlow(0L)
    val imageCacheSizeBytes: StateFlow<Long> = _imageCacheSizeBytes.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    init { refreshCacheStats() }

    fun setTheme(theme: AppTheme) = saveAndSync { repo.updateTheme(theme) }
    fun setDynamicColors(v: Boolean) = saveAndSync { repo.setDynamicColors(v) }
    fun setBiometricLock(v: Boolean) = saveAndSync { repo.setBiometricLock(v) }
    fun setSecureReader(v: Boolean) = saveAndSync { repo.setSecureReader(v) }
    fun setNotificationMode(v: NotificationDeliveryMode) = saveAndSync { repo.setNotificationDeliveryMode(v) }
    fun setWifiOnly(v: Boolean) = saveAndSync { repo.setDownloadOnWifiOnly(v) }
    fun setAutoDownload(v: Boolean) = saveAndSync { repo.setAutoDownloadNewChapters(v) }
    fun setNotifications(v: Boolean) = saveAndSync { repo.setNotificationsEnabled(v) }
    fun setAutoCleanup(v: Boolean) = saveAndSync { repo.setAutoCleanupReadDownloads(v) }
    fun setCleanupHours(v: Int) = saveAndSync { repo.setCleanupAfterHours(v) }
    fun setImageCacheLimit(limitMb: Int) = saveAndSync { repo.setImageCacheLimitMb(limitMb) }
    fun setContentBlacklist(values: Set<String>) = saveAndSync { repo.setContentBlacklist(values) }
    fun setSpoilerCollapseDefault(enabled: Boolean) = saveAndSync { repo.setSpoilerCollapseDefault(enabled) }
    fun setMutedUserIds(values: Set<String>) = saveAndSync { repo.setMutedUserIds(values) }
    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.toggleSource(id, enabled)
        runCatching { widgetDataRepository.refreshRemoteSnapshot() }
        widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
    }
    fun setReaderMode(m: ReaderMode) = saveAndSync { repo.updateReaderMode(m) }
    fun setBrightness(v: Float) = saveAndSync { repo.updateBrightness(v) }
    fun setKeepScreen(v: Boolean) = saveAndSync { repo.updateKeepScreenOn(v) }
    fun setAutoWebtoon(v: Boolean) = saveAndSync { repo.updateAutoWebtoon(v) }
    fun setIncognito(v: Boolean) = saveAndSync { repo.updateIncognitoMode(v) }
    fun setSmartPrefetch(v: Boolean) = saveAndSync { repo.updateSmartPrefetch(v) }
    fun setReaderHaptics(v: Boolean) = saveAndSync { repo.updateReaderHaptics(v) }
    fun setImageFilter(v: ReaderImageFilter) = saveAndSync { repo.updateImageFilter(v) }
    fun setAutoOpenNextChapter(v: Boolean) = saveAndSync { repo.updateAutoOpenNextChapter(v) }
    fun setShowLiveReadersOverlay(v: Boolean) = saveAndSync { repo.updateShowLiveReadersOverlay(v) }
    fun setShowReactionOverlay(v: Boolean) = saveAndSync { repo.updateShowReactionOverlay(v) }
    fun setDualPageLandscape(v: Boolean) = saveAndSync { repo.updateDualPageLandscape(v) }
    fun setWebtoonAutoStitch(v: Boolean) = saveAndSync { repo.updateWebtoonAutoStitch(v) }
    fun saveCookies(domain: String, cookies: String) = viewModelScope.launch { repo.saveCookies(domain, cookies) }

    fun refreshCacheStats() = viewModelScope.launch {
        _imageCacheSizeBytes.value = cacheManager.getImageCacheSizeBytes()
    }

    fun clearImageCache() = viewModelScope.launch {
        cacheManager.clearImageCache()
        refreshCacheStats()
    }

    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { localBackupManager.exportTo(uri) }
            .onSuccess { _backupMessage.value = stringResource(R.string.str_240) }
            .onFailure { _backupMessage.value = it.message ?: stringResource(R.string.str_336) }
    }

    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            localBackupManager.importFrom(uri)
            firebaseSyncManager.pushLocalSnapshot()
        }.onSuccess {
            _backupMessage.value = stringResource(R.string.str_238)
        }.onFailure {
            _backupMessage.value = it.message ?: stringResource(R.string.str_332)
        }
    }

    fun clearBackupMessage() { _backupMessage.value = null }

    private fun saveAndSync(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        runCatching { firebaseSyncManager.pushLocalSnapshot() }
    }
}
