package com.exapps.mangaworld.core.source.plugins

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Diagnostic access to the live plugin graph.
 *
 * The plugin state machine is where the fleet reports live, but the pieces that
 * decide it — the activation index, the registry of runners, and the boot
 * loader — are internal to the app. On-device checks (Firebase Test Lab) and
 * the in-app diagnostics screen need to look at the SAME objects the app uses,
 * not a reconstruction of them.
 *
 * This is a read-mostly surface on purpose: it exposes the stores, not a way to
 * mutate them. Mutating the index from outside the sync engine would bypass the
 * very invariants these tests exist to check.
 *
 * Precedent: `WidgetEntryPoint` (core/widget) does the same for widgets.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PluginDiagnosticsEntryPoint {
    /** Activation pointers (Room in production). */
    fun pluginIndexStore(): PluginIndexStore

    /** Which sources have a runner behind them right now. */
    fun sourceRegistry(): SourceRegistry

    /** The boot loader — used to re-run the bootstrap and assert idempotence. */
    fun bundledPluginLoader(): BundledPluginLoader
}
