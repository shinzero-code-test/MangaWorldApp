package com.exapps.mangaworld.core.source.plugins

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last-seen app version for plugin lifecycle maintenance. SharedPreferences
 * next to the sync ETag (a cache validator, not trust — same lane).
 */
class PrefsAppVersionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("plugin_sync", Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)

    fun set(version: String) {
        prefs.edit().putString(KEY, version).apply()
    }

    companion object {
        private const val KEY = "last_app_version"
    }
}

/**
 * Phase 3 upgrade reconciliation (plan §11A).
 *
 * Compatibility is re-evaluated on every app upgrade: an `INCOMPATIBLE` plugin
 * is rechecked against the NEW app/engine/bridge surface instead of staying
 * declined forever. A record that verifies now moves to `AVAILABLE` (known,
 * not installed-serving — the user opts in); anything still failing stays
 * `INCOMPATIBLE` for the next upgrade.
 *
 * Downloaded trust data stays freshness-gated on re-evaluation (an offline
 * device must not resurrect stale trust); the signature re-verifies against
 * the CURRENT rotation-aware keys, so a key revoked while the app was stale
 * cannot sneak back in.
 *
 * Pure apart from injected I/O — JVM-testable with fakes.
 */
@Singleton
class PluginUpgradeReconciler @Inject constructor(
    private val index: PluginIndexStore,
    private val store: PluginStore,
    private val trustKeys: PluginTrustKeys,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    /** Re-evaluates every `INCOMPATIBLE` record; returns how many became `AVAILABLE`. */
    suspend fun reconcile(appVersion: String): Int = withContext(io) {
        var revived = 0
        for (record in index.getAll()) {
            if (record.status != PluginStatus.INCOMPATIBLE) continue
            val bytes = record.manifestJson?.toByteArray(Charsets.UTF_8)
                ?.takeIf { it.isNotEmpty() } ?: continue
            val valid = store.verify(
                manifestBytes = bytes,
                trustedKeys = trustKeys.current(),
                host = PluginTrust.productionCapabilities(appVersion),
                enforceFreshness = true
            )
            if (valid is ManifestResult.Valid) {
                index.put(record.copy(status = PluginStatus.AVAILABLE))
                revived++
            }
        }
        revived
    }
}
