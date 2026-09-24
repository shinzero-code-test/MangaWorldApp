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
@Singleton
class SourceRegistry @Inject constructor(
    builtins: Map<String, @JvmSuppressWildcards SourcePlugin>
) {
    private val builtinMap: Map<String, SourcePlugin> = builtins.toMap()
    private val remote = ConcurrentHashMap<String, SourcePlugin>()

    /** All known plugins: built-ins plus installed remotes. */
    fun all(): List<SourcePlugin> = builtinMap.values + remote.values

    /** All known descriptors (UI lists, settings, diagnostics). */
    fun allDescriptors(): List<PluginManifest> = all().map { it.descriptor }

    fun pluginFor(id: String): SourcePlugin? = remote[id] ?: builtinMap[id]

    fun descriptorFor(id: String): PluginManifest? = pluginFor(id)?.descriptor

    fun scraperFor(id: String): MangaScraper? = pluginFor(id)?.scraper

    /** Snapshot for callers that still take a plain map (paging, diagnostics). */
    fun scraperMap(): Map<String, MangaScraper> =
        all().associate { it.descriptor.id.value to it.scraper }

    fun isKnown(id: String): Boolean = pluginFor(id) != null

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
}
