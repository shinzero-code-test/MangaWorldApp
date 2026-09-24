package com.exapps.mangaworld.presentation.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.SourceUiEntry
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceSettingsState(
    val enabledSources: Map<String, Boolean> = BuiltinSourceIds.ALL.associateWith { true },
    val notificationStates: Map<String, Boolean> = BuiltinSourceIds.ALL.associateWith { true }
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sourceUiMapper: SourceUiMapper,
    private val sourceRegistry: SourceRegistry
) : ViewModel() {

    /** Registry-driven rows (names via resolver, never hardcoded). */
    val entries: StateFlow<List<SourceUiEntry>> = flowOf(sourceUiMapper.entries())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                    enabledSources = sourceUiMapper.entries().associate { entry ->
                        entry.id to (entry.id in settings.enabledSources)
                    }
                )
            }
        }
        // Observe per-source notification settings
        viewModelScope.launch {
            sourceUiMapper.entries().forEach { entry ->
                settingsRepository.isSourceNotificationEnabled(entry.id).collect { enabled ->
                    _state.update {
                        it.copy(notificationStates = it.notificationStates + (entry.id to enabled))
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

    fun clearCookies(sourceId: String) {
        // Clear both the effective host and the descriptor-default host so stale
        // cookies never survive a domain move.
        val descriptor = sourceRegistry.descriptorFor(sourceId)
        val hosts = if (descriptor == null) {
            emptySet()
        } else {
            setOf(
                HostPolicy.hostOf(SourceDomainOverrides.baseUrlFor(sourceId, descriptor.baseUrl)),
                HostPolicy.hostOf(descriptor.baseUrl)
            ).filter { it.isNotBlank() }.toSet()
        }
        hosts.forEach { CookieCache.clearDomain(it) }
        viewModelScope.launch {
            hosts.forEach { settingsRepository.clearCookies(it) }
        }
    }

    /** Effective base URL for browser/sheet rows (follows domain overrides). */
    fun baseUrlFor(sourceId: String): String {
        val descriptor = sourceRegistry.descriptorFor(sourceId) ?: return ""
        return SourceDomainOverrides.baseUrlFor(sourceId, descriptor.baseUrl)
    }
}
