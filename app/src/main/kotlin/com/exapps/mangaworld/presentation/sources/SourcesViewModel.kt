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
    private val syncEngine: PluginSyncEngine,
    private val trustKeys: PluginTrustKeys,
    private val remoteConfig: FirebaseRemoteConfigManager,
    private val health: SourceHealthMonitor,
    private val reconciler: com.exapps.mangaworld.core.source.sync.PluginToggleReconciler,
    private val scheduler: com.exapps.mangaworld.core.source.sync.PluginSyncScheduler,
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

    private val _syncing = MutableStateFlow(false)

    /** True while an on-demand sweep runs inline in the foreground. */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _notice = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** One-shot string-resource ids for snackbars. */
    val notice: SharedFlow<Int> = _notice.asSharedFlow()

    /**
     * Refresh-tap lifecycle sink (v9.1.8): the on-device 9.1.7 refresh
     * produced zero engine lines with an enabled button, so the tap→sweep
     * chain itself went dark — these lines bracket it (tap received,
     * coroutine started, engine entered/returned/threw, flag reset).
     * Plain `android.util.Log` throws on JVM unit tests, so tests replace it.
     */
    var log: (String) -> Unit = { android.util.Log.i("SourcesSync", it) }

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
            // D6: both toggles funnel through one reconciler — enabling a
            // held row runs approval (verify/pair/register/smoke) instead of
            // a bare flip that would make the row vanish with nothing
            // registered behind it.
            if (!enabled) {
                reconciler.setEnabled(sourceId, false)
                _state.update {
                    it.copy(enabledSources = it.enabledSources + (sourceId to false))
                }
                return@launch
            }
            _busyId.value = sourceId
            val outcome = reconciler.setEnabled(sourceId, true)
            _busyId.value = null
            when (outcome) {
                is com.exapps.mangaworld.core.source.sync.PluginToggleReconciler.ToggleOutcome.Approved ->
                    _notice.emit(R.string.plugin_approved)
                is com.exapps.mangaworld.core.source.sync.PluginToggleReconciler.ToggleOutcome.ApprovalFailed ->
                    _notice.emit(R.string.plugin_approve_failed)
                else -> Unit
            }
            _state.update {
                it.copy(
                    enabledSources = it.enabledSources + (
                        sourceId to (outcome != com.exapps.mangaworld.core.source.sync.PluginToggleReconciler.ToggleOutcome.ApprovalFailed)
                    )
                )
            }
            refresh()
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

    /** On-demand distribution check (Sources action; periodic schedule owns the rest). */
    fun checkForUpdates() {
        log("refresh tapped")
        viewModelScope.launch {
            log("sweep coroutine started")
            _syncing.value = true
            try {
                // v9.1.4: run the sweep INLINE in the foreground instead of only
                // enqueueing a worker. OEM JobScheduler throttling (MIUI et al)
                // can starve background work indefinitely with zero surface —
                // a user-initiated check must not depend on it. The engine is
                // main-safe (withContext(io) throughout); the periodic worker
                // remains the background path.
                log("sweep calling engine")
                val result = syncEngine.sync(
                    trustedKeys = trustKeys.current(),
                    host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                    killSwitchJson = remoteConfig.pluginKillSwitchJson(),
                    baseDir = File(appContext.filesDir, "plugins")
                )
                log("sweep returned ${result.outcomes.size} outcomes")
                // D8: a finished manual sweep (success OR deterministic
                // failure — both "checked") resets the 12 h boot-stale window,
                // like the worker does. Transport failure stays unrecorded so
                // the stale lane can retry it.
                scheduler.recordSyncCompleted()
                val updated = result.outcomes.count { it.value is PluginSyncEngine.EntryOutcome.Updated }
                _notice.emit(
                    if (updated > 0) R.string.plugin_check_updated
                    else R.string.plugin_check_done
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                log("sweep cancelled")
                throw e
            } catch (e: com.exapps.mangaworld.core.source.sync.PluginFetcher.FetchFailure.Network) {
                log("sweep transport failure (not marking complete)")
                _notice.emit(R.string.plugin_check_failed)
            } catch (e: Exception) {
                // Deterministic abort counts as checked (worker parity) so a
                // failed sweep does not suppress the boot-stale lane.
                scheduler.recordSyncCompleted()
                log("sweep threw ${e.javaClass.name}: ${e.message?.take(160)}")
                _notice.emit(R.string.plugin_check_failed)
            } finally {
                log("sweep finally: resetting syncing")
                _syncing.value = false
                refresh()
            }
        }
    }

    /** Effective base URL for browser/sheet rows (follows domain overrides). */
    fun baseUrlFor(sourceId: String): String {
        val descriptor = sourceRegistry.descriptorFor(sourceId) ?: return ""
        return SourceDomainOverrides.baseUrlFor(sourceId, descriptor.baseUrl)
    }
}
