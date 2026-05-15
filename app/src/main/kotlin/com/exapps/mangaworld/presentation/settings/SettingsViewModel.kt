package com.exapps.mangaworld.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val appSettings: StateFlow<AppSettings> = repo.getAppSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val readerSettings: StateFlow<ReaderSettings> = repo.getReaderSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    fun setTheme(theme: AppTheme) = viewModelScope.launch { repo.updateTheme(theme) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { repo.setDownloadOnWifiOnly(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { repo.setNotificationsEnabled(v) }
    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch { repo.toggleSource(id, enabled) }
    fun setReaderMode(m: ReaderMode) = viewModelScope.launch { repo.updateReaderMode(m) }
    fun setBrightness(v: Float) = viewModelScope.launch { repo.updateBrightness(v) }
    fun setKeepScreen(v: Boolean) = viewModelScope.launch { repo.updateKeepScreenOn(v) }
    fun setAutoWebtoon(v: Boolean) = viewModelScope.launch { repo.updateAutoWebtoon(v) }
    fun saveCookies(domain: String, cookies: String) = viewModelScope.launch { repo.saveCookies(domain, cookies) }
}
