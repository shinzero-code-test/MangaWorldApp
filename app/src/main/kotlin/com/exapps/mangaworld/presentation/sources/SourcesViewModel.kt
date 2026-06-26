package com.exapps.mangaworld.presentation.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceSettingsState(
    val enabledSources: Map<String, Boolean> = MangaSource.entries.associateWith { true },
    val notificationStates: Map<String, Boolean> = MangaSource.entries.associateWith { true }
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SourceSettingsState())
    val state: StateFlow<SourceSettingsState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettings().first()
            _state.update {
                it.copy(
                    enabledSources = MangaSource.entries.associateWith { source ->
                        source.id in settings.enabledSources
                    }
                )
            }
        }
        // Observe per-source notification settings
        viewModelScope.launch {
            MangaSource.entries.forEach { source ->
                settingsRepository.isSourceNotificationEnabled(source.id).collect { enabled ->
                    _state.update {
                        it.copy(notificationStates = it.notificationStates + (source.id to enabled))
                    }
                }
            }
        }
    }

    fun toggleSource(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.toggleSource(sourceId, enabled)
            _state.update {
                it.copy(enabledSources = it.enabledSources + (sourceId to enabled))
            }
        }
    }

    fun toggleSourceNotification(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSourceNotification(sourceId, enabled)
            _state.update {
                it.copy(notificationStates = it.notificationStates + (sourceId to enabled))
            }
        }
    }

    fun clearCookies(source: MangaSource) {
        val domain = runCatching { java.net.URI(source.baseUrl).host }.getOrNull()
            ?: source.baseUrl.removePrefix("https://")
        CookieCache.clearDomain(domain)
        viewModelScope.launch {
            settingsRepository.clearCookies(domain)
        }
    }
}
