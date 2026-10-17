package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2-remaining UI row for a source: identity + resolved display bindings.
 * Built by [SourceUiMapper] from the registry so screens never touch resource IDs
 * or engine internals directly ([logoRes] 0 = letter-avatar fallback, same as before).
 */
data class SourceUiEntry(
    val id: String,
    val name: String,
    val logoRes: Int,
    val engine: SourceEngine,
    /** Cloudflare solver gate (descriptor policy, ex-enum flag). */
    val requiresVerification: Boolean,
    /** Effective host hint (follows domain overrides; display only). */
    val hostHint: String,
    /** Consent posture (v9.1.0): held/quarantined rows render badges + actions. */
    val rowState: PluginRowState = PluginRowState.SERVING
)

/**
 * Builds [SourceUiEntry] lists from the live registry (builtins as overridden by
 * verified pilots) plus index-held extras, so screens never touch resource IDs
 * or engine internals directly. Unknown ids yield null — callers skip them,
 * never fall back to another source.
 *
 * Row data is OBSERVABLE (v9.2.3). Two inputs decide what a screen shows:
 * registry membership (who can serve) and index records (status/badges/consent).
 * Both are read reactively:
 * - [SourceRegistry.revision] — registration/removal (boot resume, sync,
 *   approval, quarantine, kill switch).
 * - [PluginIndexStore.changes] — any activation-pointer write (the same set).
 *
 * The index snapshot is re-read by this singleton itself (see [init]), so even
 * one-shot readers such as `entry(id)` — the gate that decides whether a held
 * row may receive live traffic — never observe a torn or long-stale view.
 *
 * The previous design snapshotted on demand: every screen built its list once
 * (Settings even `flowOf(entries())`), so a source registered after that read
 * stayed invisible app-wide until the user re-tapped refresh several times.
 */
@Singleton
class SourceUiMapper @Inject constructor(
    private val registry: SourceRegistry,
    private val resolver: SourceDisplayResolver,
    private val indexStore: PluginIndexStore,
    @ApplicationScope scope: CoroutineScope =
        // Default is test-only: Hilt always injects @ApplicationScope.
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    /**
     * Immutable, swapped atomically. Two fields that must agree (records +
     * the extras derived from them) live in ONE value, so a concurrent reader
     * can never mix a new record map with stale extras.
     */
    private data class Snapshot(
        val records: Map<String, PluginIndexRecord> = emptyMap(),
        val heldExtras: List<SourceUiEntry> = emptyList()
    )

    private val snapshot = MutableStateFlow(Snapshot())

    init {
        // Keep the snapshot continuously fresh: every committed index write
        // re-reads it. `onStart` guarantees the first read even when nothing
        // is ever written again this process (fresh install, quiet day).
        scope.launch {
            indexStore.changes.onStart { emit(Unit) }.collect { loadSnapshot() }
        }
    }

    /** Re-reads index records + rebuilds held extras (explicit re-read path). */
    suspend fun refresh() = loadSnapshot()

    private suspend fun loadSnapshot() {
        val all = indexStore.getAll()
        val records = all.associateBy { it.id }
        val known = registry.allDescriptors().map { it.id.value }.toSet()
        val extras = all
            .filter {
                it.id !in known &&
                    (it.status == PluginStatus.DISABLED || it.status == PluginStatus.QUARANTINED) &&
                    it.activeVersion != null && it.manifestJson != null
            }
            .mapNotNull { rec -> parseManifestPreview(rec.manifestJson)?.toExtraEntry(rec.status) }
        snapshot.value = Snapshot(records, extras)
    }

    /** Registry rows + index-held extras, for the locale given. */
    fun entries(locale: String = Locale.getDefault().language): List<SourceUiEntry> =
        entriesWith(snapshot.value, locale)

    /** Row for [id], or null when unknown/dead (never a fallback source). */
    fun entry(id: String, locale: String = Locale.getDefault().language): SourceUiEntry? =
        entry(id, locale, snapshot.value)

    private fun entry(id: String, locale: String, snap: Snapshot): SourceUiEntry? {
        if (BuiltinSourceIds.isLocal(id)) return null
        val plugin = registry.pluginFor(id)
        if (plugin != null) {
            val name = resolver.name(plugin, locale) ?: return null
            val descriptor = plugin.descriptor
            return SourceUiEntry(
                id = id,
                name = name,
                logoRes = resolver.logoRes(plugin),
                engine = descriptor.engine,
                requiresVerification = descriptor.requiresVerification,
                hostHint = HostPolicy.hostOf(
                    com.exapps.mangaworld.domain.model.SourceDomainOverrides.baseUrlFor(id, descriptor.baseUrl)
                ),
                rowState = rowStateFor(snap.records[id])
            )
        }
        // Index-held extra (unregistered new id): display from the stored
        // manifest preview; approvable from the Sources sheet.
        return snap.heldExtras.firstOrNull { it.id == id }
    }

    /**
     * Rows as a stream: re-emits whenever registry membership changes or the
     * index snapshot is re-read. Consumers that filter by user settings combine
     * this with their own flow so list membership is never a snapshot artefact.
     */
    fun entriesFlow(locale: String = Locale.getDefault().language): Flow<List<SourceUiEntry>> =
        combine(registry.revision, snapshot) { _, snap -> snap }
            .map { snap -> entriesWith(snap, locale) }
            .distinctUntilChanged()

    /** Single-row stream (same triggers as [entriesFlow]). */
    fun entryFlow(id: String, locale: String = Locale.getDefault().language): Flow<SourceUiEntry?> =
        combine(registry.revision, snapshot) { _, snap -> snap }
            .map { snap -> entry(id, locale, snap) }
            .distinctUntilChanged()

    /** Consent-sheet facts for held/quarantined rows (manifest previews). */
    fun heldDetails(): Map<String, ManifestPreview> =
        detailsFrom(snapshot.value)

    /** Consent-sheet facts as a stream (same triggers as [entriesFlow]). */
    fun heldDetailsFlow(): Flow<Map<String, ManifestPreview>> =
        combine(registry.revision, snapshot) { _, snap -> detailsFrom(snap) }
            .distinctUntilChanged()

    /** Display name or null (unknown/dead sources stay invisible, never AZORA). */
    fun displayName(id: String, locale: String = Locale.getDefault().language): String? =
        entry(id, locale)?.name

    private fun entriesWith(snap: Snapshot, locale: String): List<SourceUiEntry> {
        val registryRows = registry.all().mapNotNull { entry(it.descriptor.id.value, locale, snap) }
        val extras = snap.heldExtras.filter { extra -> registryRows.none { it.id == extra.id } }
        return (registryRows + extras).sortedBy { it.name }
    }

    private fun detailsFrom(snap: Snapshot): Map<String, ManifestPreview> =
        snap.records.values
            .filter {
                (it.status == PluginStatus.DISABLED || it.status == PluginStatus.QUARANTINED ||
                    it.status == PluginStatus.INCOMPATIBLE || it.status == PluginStatus.AVAILABLE) &&
                    it.manifestJson != null
            }
            .mapNotNull { rec ->
                parseManifestPreview(rec.manifestJson)?.let { rec.id to it }
            }
            .toMap()

    private fun ManifestPreview.toExtraEntry(status: PluginStatus): SourceUiEntry? {
        val engine = SourceEngine.fromSerialName(engineName) ?: return null
        return SourceUiEntry(
            id = id,
            name = displayName(Locale.getDefault().language),
            logoRes = 0,
            engine = engine,
            requiresVerification = false,
            hostHint = HostPolicy.hostOf(baseUrl).takeIf { it.isNotBlank() } ?: id,
            rowState = if (status == PluginStatus.QUARANTINED) PluginRowState.QUARANTINED
            else PluginRowState.HELD
        )
    }
}