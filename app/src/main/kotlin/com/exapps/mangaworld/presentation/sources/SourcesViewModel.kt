package com.exapps.mangaworld.presentation.sources

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.core.source.plugins.ManifestPreview
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
import com.exapps.mangaworld.core.source.plugins.SourceHealthMonitor
import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.SourceUiEntry
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.core.source.sync.PostSmoke
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SourceSettingsState(
    val enabledSources: Map<String, Boolean> = BuiltinSourceIds.ALL.associateWith { true },
    val notificationStates: Map<String, Boolean> = BuiltinSourceIds.ALL.associateWith { true }
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sourceUiMapper: SourceUiMapper,
    private val sourceRegistry: SourceRegistry,
    private val pluginSyncScheduler: com.exapps.mangaworld.core.source.sync.PluginSyncScheduler,
    private val syncEngine: PluginSyncEngine,
    private val trustKeys: PluginTrustKeys,
    private val remoteConfig: FirebaseRemoteConfigManager,
    private val health: SourceHealthMonitor,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    /** Registry-driven rows (names via resolver, never hardcoded). */
    val entries: StateFlow<List<SourceUiEntry>> = refreshTick
        .map { sourceUiMapper.entries() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Consent-sheet facts for held/quarantined rows (manifest previews). */
    val heldDetails: StateFlow<Map<String, ManifestPreview>> = refreshTick
        .map { sourceUiMapper.heldDetails() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _state = MutableStateFlow(SourceSettingsState())
    val state: StateFlow<SourceSettingsState> = _state.asStateFlow()

    private val _busyId = MutableStateFlow<String?>(null)

    /** Id of the source with an in-flight approve/re-check, if any. */
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    private val _notice = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** One-shot string-resource ids for snackbars. */
    val notice: SharedFlow<Int> = _notice.asSharedFlow()

    init {
        refresh()
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

    /** Re-reads the plugin index snapshot, rows, details, and settings map. */
    fun refresh() {
        viewModelScope.launch {
            sourceUiMapper.refresh()
            refreshTick.emit(refreshTick.value + 1)
            val settings = settingsRepository.getAppSettings().first()
            _state.update {
                it.copy(
                    enabledSources = sourceUiMapper.entries().associate { entry ->
                        entry.id to (entry.id in settings.enabledSources)
                    }
                )
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

    /**
     * Approves a held record (v9.1.0 consent surface): re-verifies stored bytes
     * against current keys/policy, pairs the runner, registers, enables, and
     * post-smokes. Emits a result string for the snackbar, then refreshes.
     */
    fun approve(id: String) {
        viewModelScope.launch {
            _busyId.value = id
            val outcome = runCatching {
                syncEngine.approveHeld(
                    id = id,
                    baseDir = File(appContext.filesDir, "plugins"),
                    trustedKeys = trustKeys.current(),
                    host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                    killSwitchJson = remoteConfig.pluginKillSwitchJson(),
                    postSmoke = PostSmoke.Network()
                )
            }.getOrElse {
                PluginSyncEngine.ApproveOutcome.Failed(it.message?.take(120) ?: "error")
            }
            _busyId.value = null
            _notice.emit(
                if (outcome is PluginSyncEngine.ApproveOutcome.Approved) {
                    R.string.plugin_approved
                } else {
                    R.string.plugin_approve_failed
                }
            )
            refresh()
        }
    }

    /** Explicit re-smoke of a quarantined source (v9.1.0 consent surface). */
    fun recheck(id: String) {
        viewModelScope.launch {
            _busyId.value = id
            val ok = runCatching { health.reverify(id) }.getOrDefault(false)
            _busyId.value = null
            _notice.emit(if (ok) R.string.plugin_rechecked else R.string.plugin_recheck_failed)
            refresh()
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

    /** On-demand distribution check (Sources settings hook; periodic schedule owns the rest). */
    fun checkForUpdates() {
        viewModelScope.launch {
            runCatching { pluginSyncScheduler.requestNow() }
        }
    }

    /** Effective base URL for browser/sheet rows (follows domain overrides). */
    fun baseUrlFor(sourceId: String): String {
        val descriptor = sourceRegistry.descriptorFor(sourceId) ?: return ""
        return SourceDomainOverrides.baseUrlFor(sourceId, descriptor.baseUrl)
    }
}
