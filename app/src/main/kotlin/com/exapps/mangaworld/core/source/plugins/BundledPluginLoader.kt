package com.exapps.mangaworld.core.source.plugins

import android.content.Context
import android.util.Log
import com.exapps.mangaworld.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2A pilot bootstrap (+ Phase 2B installed-active resume): verifies APK-bundled
 * signed descriptors (hijala, lavascans) and registers them as verified official
 * overrides, then re-verifies and resumes index-recorded active versions from
 * previous syncs (overrides are in-memory; without this a restart would silently
 * drop a synced update and serve the builtin while the index claims vN).
 *
 * Each pilot pairs a signed manifest (identity, hosts, policy from JSON) with the
 * existing Kotlin scraper + display bindings — behavior is identical to the builtin,
 * but the metadata path (parse → canonicalize → verify → activate → supersede) is
 * the production signed pipeline. This isolates architecture bugs before any
 * network distribution (Phase 2B).
 *
 * Idempotent: same-version reinstall is a no-op success (immutable versions);
 * failures fail closed to the builtin (log + continue, never crash startup).
 */
@Singleton
class BundledPluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: SourceRegistry,
    private val store: PluginStore,
    private val index: PluginIndexStore,
    private val trustKeys: PluginTrustKeys,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    sealed interface Outcome {
        data class Activated(val id: String, val hostsExpanded: Boolean) : Outcome
        data class Skipped(val id: String, val reason: String) : Outcome
    }

    suspend fun bootstrap(): List<Outcome> = withContext(io) {
        val pilots = PILOT_IDS.map { id -> runCatching { bootstrapOne(id) }.getOrElse { e ->
            Log.w(TAG, "pilot $id failed closed: ${e.message}")
            Outcome.Skipped(id, "exception")
        } }
        val installed = index.getAll()
            .filter { it.origin == PluginOrigin.OFFICIAL && it.activeVersion != null }
            .map { record -> runCatching { resumeInstalled(record) }.getOrElse { e ->
                Log.w(TAG, "resume ${record.id} failed closed: ${e.message}")
                Outcome.Skipped(record.id, "exception")
            } }
        pilots + installed
    }

    private suspend fun bootstrapOne(id: String): Outcome {
        val bytes = readAsset("plugins/$id/v1/plugin.json")
            ?: return Outcome.Skipped(id, "asset missing")
        val builtin = registry.pluginFor(id)
            ?: return Outcome.Skipped(id, "no builtin")
        val baseDir = File(context.filesDir, "plugins")
        val result = store.install(
            baseDir = baseDir,
            manifestBytes = bytes,
            origin = PluginOrigin.OFFICIAL,
            trustedKeys = PluginTrust.pinnedKeys(),
            host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
            // Bundled payloads trust via the APK signature, not trust age.
            enforceFreshness = false,
            smoke = { manifest -> pilotSmoke(manifest, builtin.descriptor) }
        )
        return when (result) {
            is PluginStore.InstallResult.Rejected ->
                Outcome.Skipped(id, "${result.reason}: ${result.message.take(80)}")
            is PluginStore.InstallResult.Installed -> {
                val plugin = object : SourcePlugin {
                    override val descriptor = result.manifest
                    override val display = builtin.display
                    override val scraper = builtin.scraper
                }
                when (val reg = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)) {
                    is SourceRegistry.RegisterOutcome.Refused -> {
                        // Consent-gated (requiresPermission) pilots must never auto-enable:
                        // keep the verified payload staged, keep serving the builtin.
                        index.get(id)?.let { rec ->
                            index.put(rec.copy(status = PluginStatus.DISABLED))
                        }
                        Outcome.Skipped(id, reg.reason)
                    }
                    SourceRegistry.RegisterOutcome.Installed -> {
                        markEnabled(id)
                        Outcome.Activated(id, hostsExpanded = false)
                    }
                    is SourceRegistry.RegisterOutcome.Superseded -> {
                        markEnabled(id)
                        Outcome.Activated(id, reg.hostsExpanded)
                    }
                }
            }
        }
    }

    /**
     * Pilot smoke: the signed descriptor must describe the same source the builtin
     * serves (id, engine, base host). Anything else is a packaging error, not an
     * update — refuse rather than rewire a source to a stranger.
     */
    internal fun pilotSmoke(manifest: PluginManifest, builtin: PluginManifest): Boolean {
        if (manifest.id != builtin.id) return false
        if (manifest.engine != builtin.engine) return false
        if (HostPolicy.hostOf(manifest.baseUrl) != HostPolicy.hostOf(builtin.baseUrl)) return false
        return true
    }

    private suspend fun markEnabled(id: String) {
        index.get(id)?.let { rec ->
            index.put(rec.copy(status = PluginStatus.ENABLED))
        }
    }

    /**
     * Phase 2B resume: re-verify the on-disk active version (downloaded trust data
     * IS freshness-gated, unlike bundled assets) and re-register the override.
     * QUARANTINED/REVOKED/DISABLED records are never resurrected — only
     * INSTALLED/ENABLED actives resume.
     */
    private suspend fun resumeInstalled(record: PluginIndexRecord): Outcome {
        val id = record.id
        if (record.status != PluginStatus.INSTALLED && record.status != PluginStatus.ENABLED) {
            return Outcome.Skipped(id, "status ${record.status}")
        }
        val version = record.activeVersion ?: return Outcome.Skipped(id, "no active")
        val builtin = registry.pluginFor(id) ?: return Outcome.Skipped(id, "no builtin")
        val file = File(
            PluginStorage.versionDir(File(context.filesDir, "plugins").path, id, version),
            "plugin.json"
        )
        if (!file.isFile) return Outcome.Skipped(id, "payload missing")
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return Outcome.Skipped(id, "payload missing")
        val valid = when (
            val v = store.verify(
                manifestBytes = bytes,
                trustedKeys = trustKeys.current(),
                host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                enforceFreshness = true
            )
        ) {
            is ManifestResult.Invalid -> return Outcome.Skipped(id, "${v.reason}")
            is ManifestResult.Valid -> v
        }
        if (!pilotSmoke(valid.manifest, builtin.descriptor)) {
            return Outcome.Skipped(id, "smoke")
        }
        val plugin = object : SourcePlugin {
            override val descriptor = valid.manifest
            override val display = builtin.display
            override val scraper = builtin.scraper
        }
        return when (val reg = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)) {
            is SourceRegistry.RegisterOutcome.Refused -> Outcome.Skipped(id, reg.reason)
            SourceRegistry.RegisterOutcome.Installed ->
                Outcome.Activated(id, hostsExpanded = false)
            is SourceRegistry.RegisterOutcome.Superseded ->
                Outcome.Activated(id, reg.hostsExpanded)
        }
    }

    private fun readAsset(path: String): ByteArray? = runCatching {
        context.assets.open(path).use { it.readBytes() }
    }.getOrNull().also {
        if (it == null) Log.w(TAG, "bundled asset missing: $path")
    }

    companion object {
        private const val TAG = "BundledPlugins"
        val PILOT_IDS = listOf("hijala", "lavascans")
    }
}
