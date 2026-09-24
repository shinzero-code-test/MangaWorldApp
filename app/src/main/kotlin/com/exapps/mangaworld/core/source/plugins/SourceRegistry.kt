package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 runtime registry: the enum's replacement as source-of-truth for "what sources
 * exist and how to scrape them".
 *
 * Merges built-in plugins (Hilt multibindings, one `@Binds` line per source) with
 * remotely installed ones (Phase 2; empty until then). Built-ins always win id
 * collisions, so a remote payload can never shadow a shipped source.
 *
 * Thread-safe: readers (scraper IO threads, Coil, workers) share it freely.
 */
/**
 * Phase 1 runtime registry: the enum's replacement as source-of-truth for "what sources
 * exist and how to scrape them".
 *
 * Merges built-in plugins (Hilt multibindings, one `@Binds` line per source) with
 * remotely installed ones (Phase 2; empty until then) and *verified official
 * overrides* (Phase 2A: signed descriptors that supersede a builtin's metadata while
 * reusing its scraper — the update-without-release path).
 *
 * Shadowing rules (plan §8–§10):
 * - Unverified remotes ([registerRemote]) can never shadow a shipped id.
 * - Verified OFFICIAL payloads ([registerVerified]) MAY supersede a builtin: that is
 *   the whole point of signed updates. Host-set expansion is reported for the UI
 *   notice; contraction stays silent.
 * - CUSTOM/LOCAL origins can never shadow a builtin/official id, verified or not.
 *
 * Thread-safe: readers (scraper IO threads, Coil, workers) share it freely.
 */
@Singleton
class SourceRegistry @Inject constructor(
    builtins: Map<String, @JvmSuppressWildcards SourcePlugin>
) {
    private val builtinMap: Map<String, SourcePlugin> = builtins.toMap()
    private val remote = ConcurrentHashMap<String, SourcePlugin>()
    private val verifiedOverrides = ConcurrentHashMap<String, VerifiedOverride>()

    /** Verified official payload superseding a builtin (metadata from signed JSON). */
    data class VerifiedOverride(
        val plugin: SourcePlugin,
        val origin: PluginOrigin,
        /** True when the new host set strictly grows the builtin's (UI notice). */
        val hostsExpanded: Boolean
    )

    sealed interface RegisterOutcome {
        /** Installed fresh (no builtin/override existed for this id). */
        data object Installed : RegisterOutcome

        /** Superseded a builtin/previous override; [hostsExpanded] drives the notice. */
        data class Superseded(val hostsExpanded: Boolean) : RegisterOutcome

        /** Refused: custom/local shadowing, or unverified collision. */
        data class Refused(val reason: String) : RegisterOutcome
    }

    /** All known plugins: built-ins (as overridden) plus installed remotes. */
    fun all(): List<SourcePlugin> {
        val ids = (builtinMap.keys + remote.keys + verifiedOverrides.keys).toSet()
        return ids.mapNotNull { pluginFor(it) }
    }

    /** All known descriptors (UI lists, settings, diagnostics). */
    fun allDescriptors(): List<PluginManifest> = all().map { it.descriptor }

    fun pluginFor(id: String): SourcePlugin? =
        verifiedOverrides[id]?.plugin ?: remote[id] ?: builtinMap[id]

    fun descriptorFor(id: String): PluginManifest? = pluginFor(id)?.descriptor

    fun scraperFor(id: String): MangaScraper? = pluginFor(id)?.scraper

    /** Snapshot for callers that still take a plain map (paging, diagnostics). */
    fun scraperMap(): Map<String, MangaScraper> =
        all().associate { it.descriptor.id.value to it.scraper }

    fun isKnown(id: String): Boolean = pluginFor(id) != null

    /** True when [id] currently serves a verified official override (not the builtin). */
    fun isOverridden(id: String): Boolean = verifiedOverrides.containsKey(id)

    /**
     * Phase 2/Lab entry point: install a verified remote plugin at runtime.
     * Built-ins win collisions (`putIfAbsent`) — shadowing a shipped id is refused.
     */
    fun registerRemote(plugin: SourcePlugin): Boolean =
        if (builtinMap.containsKey(plugin.descriptor.id.value)) false
        else {
            remote.putIfAbsent(plugin.descriptor.id.value, plugin) == null
        }

    fun unregisterRemote(id: String): Boolean = remote.remove(id) != null

    /**
     * Phase 2A verified-install path: only OFFICIAL payloads may supersede a builtin;
     * CUSTOM/LOCAL can only add *new* ids (and only when no builtin/override claims them).
     * The payload MUST already be verified (signature + schema + smoke) — this moves
     * pointers, never trust.
     */
    fun registerVerified(plugin: SourcePlugin, origin: PluginOrigin): RegisterOutcome {
        val id = plugin.descriptor.id.value
        val builtin = builtinMap[id]
        val previous = verifiedOverrides[id]?.plugin ?: remote[id] ?: builtin
        if (origin != PluginOrigin.OFFICIAL) {
            if (builtin != null || verifiedOverrides.containsKey(id)) {
                return RegisterOutcome.Refused("custom/local must not shadow $id")
            }
            remote.putIfAbsent(id, plugin)
            return RegisterOutcome.Installed
        }
        val expanded = previous != null && hostsExpanded(previous.descriptor, plugin.descriptor)
        if (builtin == null && !verifiedOverrides.containsKey(id) && !remote.containsKey(id)) {
            verifiedOverrides[id] = VerifiedOverride(plugin, origin, expanded)
            return RegisterOutcome.Installed
        }
        verifiedOverrides[id] = VerifiedOverride(plugin, origin, expanded)
        return RegisterOutcome.Superseded(expanded)
    }

    fun clearOverride(id: String): Boolean = verifiedOverrides.remove(id) != null

    companion object {
        /** Host-set expansion (plan §8.9): growth surfaces a notice, contraction is silent. */
        fun hostsExpanded(old: PluginManifest, new: PluginManifest): Boolean =
            (new.effectiveHosts - old.effectiveHosts).isNotEmpty()

        /**
         * Consent gate (plan §8.9–10): permission-gated and non-official arrivals always
         * require explicit opt-in; official updates retain state after checks pass.
         */
        fun needsConsent(plugin: SourcePlugin, origin: PluginOrigin): Boolean =
            plugin.descriptor.requiresPermission || origin != PluginOrigin.OFFICIAL
    }
}
