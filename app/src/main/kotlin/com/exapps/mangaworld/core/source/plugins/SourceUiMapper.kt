package com.exapps.mangaworld.core.source.plugins

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
 * or engine internals directly. ViewModels inject this instead of reading the
 * retired enum. Unknown ids yield null — callers skip them, never fall back to
 * another source.
 *
 * Index data is snapshot-based ([refresh]): the registry is live but the Room
 * index is suspend-bound. Snapshots refresh on screen init, after sync checks,
 * and after approve/re-check actions — index records change only on those
 * paths, so snapshot staleness is bounded by construction.
 */
@Singleton
class SourceUiMapper @Inject constructor(
    private val registry: SourceRegistry,
    private val resolver: SourceDisplayResolver,
    private val indexStore: PluginIndexStore
) {
    private var records: Map<String, PluginIndexRecord> = emptyMap()
    private var heldExtras: List<SourceUiEntry> = emptyList()

    /** Re-reads index records + rebuilds held extras. Call on init/refresh/actions. */
    suspend fun refresh() {
        val all = indexStore.getAll()
        records = all.associateBy { it.id }
        val known = registry.allDescriptors().map { it.id.value }.toSet()
        heldExtras = all
            .filter {
                it.id !in known && it.status == PluginStatus.DISABLED &&
                    it.activeVersion != null && it.manifestJson != null
            }
            .mapNotNull { rec ->
                parseManifestPreview(rec.manifestJson)?.toExtraEntry()
            }
    }

    fun entries(locale: String = Locale.getDefault().language): List<SourceUiEntry> {
        val registryRows = registry.all().mapNotNull { entry(it.descriptor.id.value, locale) }
        val extras = heldExtras.filter { extra -> registryRows.none { it.id == extra.id } }
        return (registryRows + extras).sortedBy { it.name }
    }

    fun entry(id: String, locale: String = Locale.getDefault().language): SourceUiEntry? {
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
                rowState = rowStateFor(records[id])
            )
        }
        // Index-held extra (unregistered new id): display from the stored
        // manifest preview; approvable from the Sources sheet.
        return heldExtras.firstOrNull { it.id == id }
    }

    /** Previews backing consent sheets, keyed by id (held + quarantined only). */
    fun heldDetails(): Map<String, ManifestPreview> =
        records.values
            .filter {
                (it.status == PluginStatus.DISABLED || it.status == PluginStatus.QUARANTINED) &&
                    it.manifestJson != null
            }
            .mapNotNull { rec ->
                parseManifestPreview(rec.manifestJson)?.let { rec.id to it }
            }
            .toMap()

    /** Display name or null (unknown/dead sources stay invisible, never AZORA). */
    fun displayName(id: String, locale: String = Locale.getDefault().language): String? =
        entry(id, locale)?.name

    private fun ManifestPreview.toExtraEntry(): SourceUiEntry? {
        val engine = SourceEngine.fromSerialName(engineName) ?: return null
        return SourceUiEntry(
            id = id,
            name = displayName(Locale.getDefault().language),
            logoRes = 0,
            engine = engine,
            requiresVerification = false,
            hostHint = HostPolicy.hostOf(baseUrl).takeIf { it.isNotBlank() } ?: id,
            rowState = PluginRowState.HELD
        )
    }
}
