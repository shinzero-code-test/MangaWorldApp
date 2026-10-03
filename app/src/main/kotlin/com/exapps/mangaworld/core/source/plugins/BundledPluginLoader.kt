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
 *
 * Phase 3 additions: index-recorded NEW ids (no builtin) resume through the
 * runner factory (descriptor/script runners rebuilt from on-disk payloads);
 * app upgrades re-evaluate `INCOMPATIBLE` records via [PluginUpgradeReconciler].
 */
@Singleton
class BundledPluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: SourceRegistry,
    private val store: PluginStore,
    private val index: PluginIndexStore,
    private val trustKeys: PluginTrustKeys,
    private val runnerFactory: PluginRunnerFactory,
    private val reconciler: PluginUpgradeReconciler,
    private val appVersionStore: PrefsAppVersionStore,
    private val telemetry: PluginTelemetry,
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
        // D10: a concurrent sweep may have persisted records after the
        // snapshot above — resume anything serving-nothing that this boot has
        // not already handled, instead of leaving it dark until next boot.
        // (Anything the sweep registered concurrently is skipped: the fresh
        // state wins by construction.)
        val handled = pilots.mapNotNull {
            (it as? Outcome.Activated)?.id
        }.toSet() + installed.mapNotNull { (it as? Outcome.Activated)?.id }.toSet()
        val missed = index.getAll()
            .filter {
                it.origin == PluginOrigin.OFFICIAL && it.activeVersion != null &&
                    it.id !in handled && registry.pluginFor(it.id) == null
            }
            .map { record -> runCatching { resumeInstalled(record) }.getOrElse { e ->
                Log.w(TAG, "resume ${record.id} failed closed: ${e.message}")
                Outcome.Skipped(record.id, "exception")
            } }
        val revived = runCatching { reconcileUpgrades() }.getOrElse { e ->
            Log.w(TAG, "upgrade reconciliation failed closed: ${e.message}")
            0
        }
        // Gate 0 fleet telemetry: one aggregate line per boot (activated ids
        // bounded; skips collapsed by reason) instead of per-id logcat only.
        val outcomes = pilots + installed + missed
        telemetry.logBootstrap(
            activatedIds = outcomes
                .filterIsInstance<Outcome.Activated>()
                .map { it.id }.take(20),
            skippedByReason = outcomes
                .filterIsInstance<Outcome.Skipped>()
                .groupingBy { it.reason }
                .eachCount(),
            reconciled = revived,
            appVersion = BuildConfig.VERSION_NAME
        )
        outcomes
    }

    /**
     * App-upgrade hook (§11A): first boot on a new version re-evaluates
     * `INCOMPATIBLE` records instead of leaving them declined forever.
     */
    private suspend fun reconcileUpgrades(): Int {
        val current = BuildConfig.VERSION_NAME
        if (appVersionStore.get() == current) return 0
        val revived = reconciler.reconcile(current)
        if (revived > 0) Log.i(TAG, "upgrade to $current revived $revived incompatible plugin(s)")
        appVersionStore.set(current)
        return revived
    }

    private suspend fun bootstrapOne(id: String): Outcome {
        val bytes = readAsset("plugins/$id/v1/plugin.json")
            ?: return Outcome.Skipped(id, "asset missing")
        val builtin = registry.pluginFor(id)
            ?: return Outcome.Skipped(id, "no builtin")
        // F4: safety/user-controlled states win over the APK asset — a
        // kill-switch REVOKED, quarantined, or explicitly DISABLED pilot must
        // not be re-activated (and re-enabled) by every process start.
        // No record (first install) still bootstraps.
        index.get(id)?.let { rec ->
            if (rec.status == PluginStatus.QUARANTINED ||
                rec.status == PluginStatus.REVOKED ||
                rec.status == PluginStatus.DISABLED
            ) {
                return Outcome.Skipped(id, "blocked: ${rec.status}")
            }
        }
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
     * Disk-rebuild result: the shared core of boot resume and quarantine
     * reverify. Both re-verify retained bytes (signature + immutability;
     * freshness applies at acquisition, never to stored bytes) and re-pair
     * the runner — neither path writes registry/index here; callers decide
     * registration and status.
     */
    internal sealed interface DiskBuild {
        data class Ready(val plugin: SourcePlugin, val isOverride: Boolean) : DiskBuild
        data object NoRecord : DiskBuild
        data object NoPayload : DiskBuild
        data class Unverified(val reason: String) : DiskBuild
        data class Gated(val reason: String) : DiskBuild
        data object NoRunner : DiskBuild
    }

    internal suspend fun buildPluginFromDisk(id: String): DiskBuild {
        val record = index.get(id) ?: return DiskBuild.NoRecord
        val version = record.activeVersion ?: return DiskBuild.NoPayload
        val dir = File(
            PluginStorage.versionDir(File(context.filesDir, "plugins").path, id, version)
        )
        val bytes = File(dir, "plugin.json").takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: return DiskBuild.NoPayload
        val valid = when (
            val v = store.verify(
                manifestBytes = bytes,
                trustedKeys = trustKeys.current(),
                host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                enforceFreshness = false
            )
        ) {
            is ManifestResult.Invalid -> return DiskBuild.Unverified("${v.reason}")
            is ManifestResult.Valid -> v
        }
        val builtin = registry.pluginFor(id)
        if (builtin != null) {
            if (valid.manifest.engine != builtin.descriptor.engine) {
                return DiskBuild.Gated("engine changed")
            }
            if (!pilotSmoke(valid.manifest, builtin.descriptor)) {
                return DiskBuild.Gated("smoke")
            }
            return DiskBuild.Ready(
                object : SourcePlugin {
                    override val descriptor = valid.manifest
                    override val display = builtin.display
                    override val scraper = builtin.scraper
                },
                isOverride = true
            )
        }
        val scraper = buildResumeRunner(valid.manifest, dir) ?: return DiskBuild.NoRunner
        return DiskBuild.Ready(
            object : SourcePlugin {
                override val descriptor = valid.manifest
                override val display = runnerFactory.remoteDisplay()
                override val scraper = scraper
            },
            isOverride = false
        )
    }

    /**
     * Phase 2B resume (+ Phase 3 new-id runners): re-verify the on-disk active
     * version and re-register it.
     *
     * - Overrides (a builtin claims the id): metadata supersede with the builtin
     *   scraper; QUARANTINED/REVOKED/DISABLED records are never resurrected —
     *   only INSTALLED/ENABLED actives resume (the builtin keeps serving).
     * - New ids (no builtin): the registry would otherwise be empty until the
     *   next sync, so INSTALLED/ENABLED/DISABLED records all resume through the
     *   runner factory, preserving the recorded status (a fresh DISABLED stays
     *   DISABLED — visible but opted out).
     */
    private suspend fun resumeInstalled(record: PluginIndexRecord): Outcome {
        val id = record.id
        if (record.status != PluginStatus.INSTALLED &&
            record.status != PluginStatus.ENABLED &&
            record.status != PluginStatus.DISABLED
        ) {
            return Outcome.Skipped(id, "status ${record.status}")
        }
        if (record.activeVersion == null) return Outcome.Skipped(id, "no active")
        if (registry.pluginFor(id) != null && record.status == PluginStatus.DISABLED) {
            // Override path: disabled stays with the builtin (never resurrected).
            return Outcome.Skipped(id, "disabled override")
        }
        val built = buildPluginFromDisk(id)
        val plugin: SourcePlugin
        val preserveDisabled: Boolean
        when (built) {
            is DiskBuild.Ready -> {
                plugin = built.plugin
                preserveDisabled = !built.isOverride && record.status == PluginStatus.DISABLED
            }
            is DiskBuild.NoRecord -> return Outcome.Skipped(id, "record gone")
            is DiskBuild.NoPayload -> return Outcome.Skipped(id, "payload missing")
            is DiskBuild.Unverified -> return Outcome.Skipped(id, built.reason)
            is DiskBuild.Gated -> return Outcome.Skipped(id, built.reason)
            is DiskBuild.NoRunner -> return Outcome.Skipped(id, "no runner")
        }
        // D10: the sweep may have moved this id while we verified — re-read
        // and back off instead of registering stale metadata over fresh state.
        // (Pointer moves only; a status flip alone does not invalidate bytes.)
        if (index.get(id)?.activeVersion != record.activeVersion) {
            return Outcome.Skipped(id, "superseded mid-resume")
        }
        return when (val reg = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)) {
            is SourceRegistry.RegisterOutcome.Refused -> Outcome.Skipped(id, reg.reason)
            SourceRegistry.RegisterOutcome.Installed -> {
                if (!preserveDisabled) markEnabled(id)
                Outcome.Activated(id, hostsExpanded = false)
            }
            is SourceRegistry.RegisterOutcome.Superseded -> {
                if (!preserveDisabled) markEnabled(id)
                Outcome.Activated(id, reg.hostsExpanded)
            }
        }
    }

    /** Runner rebuild for new ids: descriptor engines pair directly; scripts
     * re-verify their hash against the manifest pin before loading. */
    private fun buildResumeRunner(
        manifest: PluginManifest,
        dir: File
    ): com.exapps.mangaworld.core.data.remote.scraper.MangaScraper? = when (manifest.engine) {
        SourceEngine.MADARA, SourceEngine.MANGAREADER ->
            runnerFactory.createDescriptor(manifest)
        SourceEngine.SCRIPT -> {
            val js = File(dir, "source.js").takeIf { it.isFile }
                ?.let { runCatching { it.readBytes() }.getOrNull() }
                ?: return null
            runnerFactory.createScript(manifest, js)?.getOrNull()
        }
        else -> null
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
