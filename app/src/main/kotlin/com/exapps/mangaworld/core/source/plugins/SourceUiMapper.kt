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
    val hostHint: String
)

/**
 * Builds [SourceUiEntry] lists from the live registry (builtins as overridden by
 * verified pilots). ViewModels inject this instead of reading the retired enum.
 * Unknown ids yield null — callers skip them, never fall back to another source.
 */
@Singleton
class SourceUiMapper @Inject constructor(
    private val registry: SourceRegistry,
    private val resolver: SourceDisplayResolver
) {
    fun entries(locale: String = Locale.getDefault().language): List<SourceUiEntry> =
        registry.all().mapNotNull { entry(it.descriptor.id.value, locale) }
            .sortedBy { it.name }

    fun entry(id: String, locale: String = Locale.getDefault().language): SourceUiEntry? {
        if (BuiltinSourceIds.isLocal(id)) return null
        val plugin = registry.pluginFor(id) ?: return null
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
            )
        )
    }

    /** Display name or null (unknown/dead sources stay invisible, never AZORA). */
    fun displayName(id: String, locale: String = Locale.getDefault().language): String? =
        entry(id, locale)?.name
}
