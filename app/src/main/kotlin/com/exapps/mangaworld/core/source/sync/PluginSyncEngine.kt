package com.exapps.mangaworld.core.source.sync

import android.content.Context
import android.util.Log
import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginDistribution
import com.exapps.mangaworld.core.source.plugins.PluginIndexEntry
import com.exapps.mangaworld.core.source.plugins.PluginIndexParser
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginKillSwitch
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStorage
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.SourcePlugin
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conditional-poll ETag storage. SharedPreferences in prod, fake in tests.
 * A stored ETag is only a cache validator — never trust, only bandwidth.
 */
interface EtagStore {
    fun get(): String?
    fun set(etag: String?)
}

@Singleton
class PrefsEtagStore @Inject constructor(
    @ApplicationContext context: Context
) : EtagStore {
    private val prefs = context.getSharedPreferences("plugin_sync", Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(etag: String?) {
        prefs.edit().putString(KEY, etag).apply()
    }

    companion object {
        private const val KEY = "index_etag"
    }
}

/**
 * Post-activation read smoke: the previous verified version is retained until
 * the new one proves it can serve. Failures roll back automatically.
 */
sealed interface PostSmoke {
    /** Live read probe (default in prod): `getHomeData` must succeed in budget. */
    data class Network(val timeoutMs: Long = 60_000L) : PostSmoke

    /** Tests / Lab: caller-decided. */
    data class Custom(val fn: suspend (SourcePlugin) -> Boolean) : PostSmoke

    /** Skip the probe (tests proving pointer mechanics, never prod). */
    data object Disabled : PostSmoke
}

/**
 * Phase 2B sync engine: untrusted-index poll → verified activation.
 *
 * Per sync (plan §8):
 * 1. Apply kill-switches first (cheap, local, fail-closed direction).
 * 2. ETag-conditional GET of `index.json`; 304 ends the sync early.
 * 3. For each changed id: version compare (downgrades refused — explicit
 *    `rollback()` is the downgrade path) → same-host manifest download →
 *    verify (signature/schema/compat/freshness) → engine-kill + consent gates →
 *    persist → pair with the builtin scraper → verified override → read smoke.
 * 4. New ids without a builtin have no runner in 2B (generic theme runners are
 *    deferred): the verified payload is retained DISABLED, never registered —
 *    no phantom sources, no shadowing.
 * 5. Remote arrivals install DISABLED and never auto-enable; official updates
 *    retain their serving state after every gate passes. `requiresPermission`
 *    updates are held for explicit consent. Host-set expansion is logged (and
 *    reported in the result) as the non-blocking notice.
 *
 * Every entry is isolated in `runCatching`: one bad candidate can never abort
 * the sweep. Only index-level transport failures throw (the worker retries those).
 */
@Singleton
class PluginSyncEngine @Inject constructor(
    private val indexStore: PluginIndexStore,
    private val pluginStore: PluginStore,
    private val registry: SourceRegistry,
    private val fetcher: PluginFetcher,
    private val etags: EtagStore,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    sealed interface EntryOutcome {
        data object UpToDate : EntryOutcome
        data class Updated(val hostsExpanded: Boolean) : EntryOutcome
        data object HeldForConsent : EntryOutcome
        data object NewSourceDeferred : EntryOutcome
        data object RolledBack : EntryOutcome
        data object Revoked : EntryOutcome
        data object DowngradeRefused : EntryOutcome
        data class Rejected(val reason: String) : EntryOutcome
        data class Skipped(val reason: String) : EntryOutcome
        data class Failed(val reason: String) : EntryOutcome
    }

    data class SyncResult(
        val indexNotModified: Boolean = false,
        val outcomes: Map<String, EntryOutcome> = emptyMap(),
        val revocationsApplied: List<String> = emptyList()
    )

    /**
     * @param trustedKeys pinned + RC-merged verification keys (rotation-aware).
     * @param host production capabilities for the compat gate.
     * @param indexUrl distribution index (default: dashboard origin).
     * @param killSwitchJson RC `plugin_kill_switch` payload (null = no policy).
     * @param postSmoke read probe after activation.
     * @param allowInsecure test-only http (MockWebServer); never true in prod.
     * @param baseDir plugin files root (`files/plugins` in prod).
     */
    @Suppress("LongParameterList")
    suspend fun sync(
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        indexUrl: String = PluginDistribution.indexUrl(),
        killSwitchJson: String? = null,
        postSmoke: PostSmoke = PostSmoke.Network(),
        allowInsecure: Boolean = false,
        baseDir: File
    ): SyncResult = withContext(io) {
        val policy = PluginKillSwitch.parse(killSwitchJson)
        val revocations = applyKillSwitch(policy)

        val indexHost = hostOf(indexUrl)
            ?: throw PluginFetcher.FetchFailure.Insecure(indexUrl)
        val headers = buildMap {
            etags.get()?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
        }
        val indexBytes = try {
            fetcher.get(
                url = indexUrl,
                allowedHosts = setOf(indexHost),
                maxBytes = PluginIndexParser.MAX_INDEX_BYTES.toLong(),
                headers = headers
            )
        } catch (e: OkHttpPluginFetcher.NotModified) {
            return@withContext SyncResult(
                indexNotModified = true,
                revocationsApplied = revocations
            )
        }
        val parsed = when (val r = PluginIndexParser.parse(indexBytes.body)) {
            is PluginIndexParser.IndexResult.Valid -> r
            is PluginIndexParser.IndexResult.Invalid ->
                throw IllegalStateException("index rejected: ${r.reason}")
        }
        indexBytes.etag?.let { etags.set(it) }

        val outcomes = mutableMapOf<String, EntryOutcome>()
        for (entry in parsed.entries) {
            outcomes[entry.id] = runCatching {
                syncEntry(
                    entry = entry,
                    indexUrl = indexUrl,
                    trustedKeys = trustedKeys,
                    host = host,
                    policy = policy,
                    postSmoke = postSmoke,
                    allowInsecure = allowInsecure,
                    baseDir = baseDir
                )
            }.getOrElse { e ->
                Log.w(TAG, "sync ${entry.id} failed: ${e.message}")
                EntryOutcome.Failed(e.message?.take(160) ?: "unknown")
            }
        }
        SyncResult(outcomes = outcomes, revocationsApplied = revocations)
    }

    // ─── Kill-switch application ────────────────────────────────────────────

    private suspend fun applyKillSwitch(policy: PluginKillSwitch.Policy): List<String> {
        val applied = mutableListOf<String>()
        for (rev in policy.revoked) {
            val record = indexStore.get(rev.id) ?: continue
            val active = record.activeVersion ?: continue
            if (rev.version != null && rev.version != active) continue
            if (registry.isOverridden(rev.id)) {
                registry.clearOverride(rev.id)
            }
            // Remote/custom installs are also unregistered; builtins simply
            // resume (their kill-switch stays `source_<id>_enabled`).
            registry.unregisterRemote(rev.id)
            indexStore.put(record.copy(status = PluginStatus.REVOKED))
            applied += rev.id
            Log.w(TAG, "kill-switch revoked ${rev.id} (was v$active)")
        }
        if (policy.disableAllCustoms) {
            for (record in indexStore.getAll()) {
                if (record.origin == PluginOrigin.OFFICIAL) continue
                if (registry.unregisterRemote(record.id)) {
                    indexStore.put(record.copy(status = PluginStatus.DISABLED))
                    applied += "${record.id} (customs off)"
                }
            }
        }
        return applied
    }

    // ─── Per-entry sync ─────────────────────────────────────────────────────

    private suspend fun syncEntry(
        entry: PluginIndexEntry,
        indexUrl: String,
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        policy: PluginKillSwitch.Policy,
        postSmoke: PostSmoke,
        allowInsecure: Boolean,
        baseDir: File
    ): EntryOutcome {
        if (PluginKillSwitch.isRevoked(policy, entry.id, entry.version)) {
            applyKillSwitch(PluginKillSwitch.Policy(revoked = listOf(
                PluginKillSwitch.Revocation(entry.id, entry.version)
            )))
            return EntryOutcome.Revoked
        }
        if (com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds.isLocal(entry.id)) {
            return EntryOutcome.Skipped("local id")
        }
        if (com.exapps.mangaworld.core.source.plugins.EngineCompatibility.compareVersions(
                host.appVersion, entry.minAppVersion
            ) < 0
        ) {
            return EntryOutcome.Skipped("requires app ${entry.minAppVersion}")
        }
        val record = indexStore.get(entry.id)
        val current = record?.activeVersion
            ?: registry.descriptorFor(entry.id)?.version
        if (current != null) {
            if (entry.version == current) return EntryOutcome.UpToDate
            // Downgrades ride the explicit rollback path (previously-signed
            // version re-point), never the forward sync.
            if (entry.version < current) {
                Log.w(TAG, "sync ${entry.id}: index v${entry.version} < active v$current — refused")
                return EntryOutcome.DowngradeRefused
            }
        }
        PluginDistribution.checkManifestUrl(entry.manifestUrl, indexUrl, allowInsecure)?.let {
            return EntryOutcome.Rejected("manifest url: $it")
        }
        val indexHost = hostOf(indexUrl)
            ?: return EntryOutcome.Rejected("unparseable indexUrl")
        val manifestBytes = try {
            fetcher.get(
                url = entry.manifestUrl,
                // Same-host rule doubles as the fetch allow-list.
                allowedHosts = setOf(indexHost),
                maxBytes = ManifestParserMaxBytes.toLong()
            ).body
        } catch (e: PluginFetcher.FetchFailure) {
            return EntryOutcome.Failed("manifest fetch: ${e.message}")
        }
        val valid = when (
            val v = pluginStore.verify(
                manifestBytes = manifestBytes,
                trustedKeys = trustedKeys,
                host = host,
                enforceFreshness = true
            )
        ) {
            is ManifestResult.Invalid -> return EntryOutcome.Rejected("${v.reason}: ${v.message.take(120)}")
            is ManifestResult.Valid -> v
        }
        // Defense in depth: the manifest must bind to the hint that found it.
        if (!PluginStorage.authorizeInstall(entry, valid)) {
            return EntryOutcome.Rejected("index/manifest id+version mismatch")
        }
        val manifest = valid.manifest
        if (PluginDistribution.kindEngineSkew(entry.kind, manifest.engine)) {
            Log.w(TAG, "sync ${entry.id}: index kind '${entry.kind}' vs engine '${manifest.engine.serialName}' — manifest wins")
        }
        if (manifest.engine in policy.disabledEngines) {
            return EntryOutcome.Rejected("engine ${manifest.engine.serialName} disabled by kill-switch")
        }
        if (manifest.requiresPermission) {
            // Never auto-install consent-gated payloads: persist verified bytes
            // for review, keep serving the current version.
            persistQuietly(baseDir, entry, manifest, manifestBytes)
            return EntryOutcome.HeldForConsent
        }
        val builtin = registry.pluginFor(entry.id)
        if (builtin == null) {
            // 2B has no generic theme runners yet: retain the verified payload
            // DISABLED for a future runner, register nothing (no phantom source).
            persistQuietly(baseDir, entry, manifest, manifestBytes, enabled = false)
            Log.w(TAG, "sync ${entry.id}: no builtin runner — payload retained, registration deferred")
            return EntryOutcome.NewSourceDeferred
        }
        when (
            val persisted = pluginStore.persistVerified(
                baseDir = baseDir,
                id = entry.id,
                manifest = manifest,
                manifestBytes = manifestBytes,
                origin = PluginOrigin.OFFICIAL
            )
        ) {
            is PluginStore.InstallResult.Rejected ->
                return EntryOutcome.Rejected("${persisted.reason}: ${persisted.message.take(120)}")
            is PluginStore.InstallResult.Installed -> Unit
        }
        val plugin = object : SourcePlugin {
            override val descriptor: PluginManifest = manifest
            override val display = builtin.display
            override val scraper = builtin.scraper
        }
        val outcome = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)
        if (outcome is SourceRegistry.RegisterOutcome.Refused) {
            return EntryOutcome.Rejected(outcome.reason)
        }
        val expanded = (outcome as? SourceRegistry.RegisterOutcome.Superseded)?.hostsExpanded == true
        if (expanded) {
            // Plan §8.9 non-blocking notice: logged + reported; UI surfacing follows.
            Log.w(TAG, "sync ${entry.id}: official update expands host set — notice")
        }
        indexStore.get(entry.id)?.let { rec ->
            indexStore.put(rec.copy(status = PluginStatus.ENABLED))
        }
        if (!runPostSmoke(plugin, postSmoke)) {
            // Previous verified version retained by construction: clear the
            // override (builtin resumes) and quarantine the candidate.
            registry.clearOverride(entry.id)
            indexStore.get(entry.id)?.let { rec ->
                indexStore.put(rec.copy(status = PluginStatus.QUARANTINED))
            }
            Log.w(TAG, "sync ${entry.id}: post-activation smoke failed — rolled back")
            return EntryOutcome.RolledBack
        }
        return EntryOutcome.Updated(hostsExpanded = expanded)
    }

    /** Verified persist without activation (consent holds, future runners). */
    private suspend fun persistQuietly(
        baseDir: File,
        entry: PluginIndexEntry,
        manifest: PluginManifest,
        manifestBytes: ByteArray,
        enabled: Boolean = false
    ) {
        when (pluginStore.persistVerified(baseDir, entry.id, manifest, manifestBytes, PluginOrigin.OFFICIAL)) {
            is PluginStore.InstallResult.Installed -> {
                indexStore.get(entry.id)?.let { rec ->
                    indexStore.put(
                        rec.copy(
                            status = if (enabled) PluginStatus.ENABLED else PluginStatus.DISABLED
                        )
                    )
                }
            }
            is PluginStore.InstallResult.Rejected -> Unit
        }
    }

    private suspend fun runPostSmoke(plugin: SourcePlugin, postSmoke: PostSmoke): Boolean =
        when (postSmoke) {
            is PostSmoke.Disabled -> true
            is PostSmoke.Custom -> runCatching { postSmoke.fn(plugin) }.getOrDefault(false)
            is PostSmoke.Network -> runCatching {
                withTimeout(postSmoke.timeoutMs) {
                    plugin.scraper.getHomeData().isSuccess
                }
            }.getOrDefault(false)
        }

    private fun hostOf(url: String): String? {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
            ?.lowercase()?.trimEnd('.')
        return host?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "PluginSync"
        const val ManifestParserMaxBytes: Int =
            com.exapps.mangaworld.core.source.plugins.ManifestParser.MAX_MANIFEST_BYTES
    }
}
