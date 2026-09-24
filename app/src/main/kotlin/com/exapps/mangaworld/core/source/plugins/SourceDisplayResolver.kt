package com.exapps.mangaworld.core.source.plugins

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-layer display resolution for plugins (replaces enum `nameRes`/`logoRes` call sites).
 *
 * Name precedence: requested locale → Arabic → APK string resource. Remote `logoUrl`
 * (Phase 2, Coil-loaded) is resolved by callers; this only serves bundled fallbacks.
 */
@Singleton
class SourceDisplayResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun name(plugin: SourcePlugin, locale: String = "ar"): String =
        plugin.descriptor.names[locale]
            ?: plugin.descriptor.names["ar"]
            ?: context.getString(plugin.display.nameRes)

    fun nameFor(registry: SourceRegistry, id: String, locale: String = "ar"): String? =
        registry.pluginFor(id)?.let { name(it, locale) }

    fun logoRes(plugin: SourcePlugin): Int = plugin.display.logoRes

    fun logoResFor(registry: SourceRegistry, id: String): Int? =
        registry.pluginFor(id)?.let { logoRes(it) }
}
