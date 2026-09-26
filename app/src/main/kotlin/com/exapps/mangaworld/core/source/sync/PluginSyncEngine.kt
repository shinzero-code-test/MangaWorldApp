package com.exapps.mangaworld.core.source.sync

import android.content.Context
import android.util.Log
import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginDistribution
import com.exapps.mangaworld.core.source.plugins.PluginIndexEntry
import com.exapps.mangaworld.core.source.plugins.PluginIndexParser
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginKillSwitch
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.plugins.PluginRunnerFactory
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStorage
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourcePlugin
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.script.ScriptContract
import com.exapps.mangaworld.domain.repository.SettingsRepository
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
 *    script download + hash pin (script kind) → persist → runner pairing
 *    (builtin scraper for same-engine overrides, generic runner for new ids) →
 *    verified registration → read smoke.
 * 4. New ids pair a runner when the engine has one (Phase 3: MADARA/MANGAREADER
 *    descriptors, SCRIPT). Engines without a generic runner (ASTRO/API/CUSTOM)
 *    keep the 2B behavior: verified payload retained DISABLED, never registered.
 * 5. Remote arrivals honor `enabledByDefault` on first install (never auto-enable
 *    beyond that); official updates retain their serving state — an explicit
 *    user DISABLED survives an update. `requiresPermission` updates and
 *    engine changes on override are held for explicit consent. Host-set
 *    expansion is logged (and reported in the result) as the non-blocking notice.
 * 6. End of sweep: quota enforcement evicts only non-active/non-previous
 *    versions (installs can never delete what they superseded).
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
    private val runnerFactory: PluginRunnerFactory,
    private val settingsRepo: SettingsRepository,
    private val telemetry: com.exapps.mangaworld.core.source.plugins.PluginTelemetry,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    /**
     * Log sink. Defaults to logcat; tests replace it with a collector (raw
     * `android.util.Log` throws on plain JVM — the android.jar stubs are not
     * mocked — so production code in this package must never call it directly).
     * Internal for test access; production never reassigns.
     */
    internal var log: (String) -> Unit = { android.util.Log.w(TAG, it) }

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
        val revocationsApplied: List<String> = emptyList(),
        /** Old versions evicted by the post-sweep quota pass. */
        val evictedVersions: Int = 0
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
    ): SyncResult {
        // Gate 0 fleet telemetry: every sweep emits one aggregate report —
        // including 304 short-circuits (heartbeat) and transport aborts —
        // so production behavior is observable, not logcat-only.
        val start = System.currentTimeMillis()
        return try {
            val result = syncInner(
                trustedKeys = trustedKeys,
                host = host,
                indexUrl = indexUrl,
                killSwitchJson = killSwitchJson,
                postSmoke = postSmoke,
                allowInsecure = allowInsecure,
                baseDir = baseDir
            )
            telemetry.logSync(reportOf(result, System.currentTimeMillis() - start))
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            telemetry.logSync(
                com.exapps.mangaworld.core.source.plugins.PluginSyncReport(
                    indexNotModified = false,
                    outcomeCounts = mapOf("SyncAborted" to 1),
                    failedIds = emptyList(),
                    rejectedIds = emptyList(),
                    revocations = emptyList(),
                    evictedVersions = 0,
                    durationMs = System.currentTimeMillis() - start
                )
            )
            throw e
        }
    }

    private fun reportOf(result: SyncResult, durationMs: Long) =
        com.exapps.mangaworld.core.source.plugins.PluginSyncReport(
            indexNotModified = result.indexNotModified,
            outcomeCounts = result.outcomes.values
                .groupingBy { it.javaClass.simpleName }
                .eachCount(),
            failedIds = result.outcomes
                .filterValues { it is EntryOutcome.Failed }
                .keys.take(10).toList(),
            rejectedIds = result.outcomes
                .filterValues { it is EntryOutcome.Rejected }
                .keys.take(10).toList(),
            revocations = result.revocationsApplied,
            evictedVersions = result.evictedVersions,
            durationMs = durationMs
        )

    @Suppress("LongParameterList")
    private suspend fun syncInner(
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        indexUrl: String = PluginDistribution.indexUrl(),
        killSwitchJson: String? = null,
        postSmoke: PostSmoke = PostSmoke.Network(),
        allowInsecure: Boolean = false,
        baseDir: File
    ): SyncResult = withContext(io) {
        val policy = PluginKillSwitch.parse(killSwitchJson)
        // Revocations first (cheap, local, fail-closed), then engine kills on
        // actives: an UpToDate short-circuit must never let a killed engine
        // keep serving until the next version bump (F-review).
        val revocations = applyKillSwitch(policy) + enforceEngineKills(policy)

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
                log("sync ${entry.id} failed: ${e.message}")
                EntryOutcome.Failed(e.message?.take(160) ?: "unknown")
            }
        }
        // Quota runs on a schedule (post-sweep), never inline on install: it can
        // only remove versions that are neither active nor rollback-eligible.
        val evicted = runCatching {
            pluginStore.evictIfOverBudget(baseDir, PluginStorage.STORAGE_BUDGET_BYTES)
        }.getOrElse { e ->
            log("quota sweep failed: ${e.message}")
            null
        }?.evict?.size ?: 0
        if (evicted > 0) log("quota sweep evicted $evicted old version(s)")
        SyncResult(outcomes = outcomes, revocationsApplied = revocations, evictedVersions = evicted)
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
            log("kill-switch revoked ${rev.id} (was v$active)")
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

    /**
     * Engine-level kill-switch on ACTIVES (F-review: the candidate gate in
     * `syncEntry` never sees UpToDate records). Any served remote payload whose
     * manifest engine is disabled is unregistered and marked REVOKED — the same
     * removal primitives as revocation. Pure builtins (no index record) keep
     * their existing per-source `source_<id>_enabled` kill-switch; an engine
     * kill never disables APK-shipped scrapers (that path stays an app release).
     */
    private suspend fun enforceEngineKills(policy: PluginKillSwitch.Policy): List<String> {
        if (policy.disabledEngines.isEmpty()) return emptyList()
        val applied = mutableListOf<String>()
        for (record in indexStore.getAll()) {
            if (record.activeVersion == null) continue
            if (record.status != PluginStatus.ENABLED && record.status != PluginStatus.INSTALLED) continue
            val engine = record.manifestJson?.let(::parseManifestEngine) ?: continue
            if (engine !in policy.disabledEngines) continue
            if (registry.isOverridden(record.id)) {
                registry.clearOverride(record.id)
            }
            registry.unregisterRemote(record.id)
            indexStore.put(record.copy(status = PluginStatus.REVOKED))
            applied += "${record.id} (engine ${engine.serialName})"
            log("kill-switch disabled engine ${engine.serialName}: revoked ${record.id} (was v${record.activeVersion})")
        }
        return applied
    }

    /** Lenient engine read for enforcement (bytes were verified at install). */
    private fun parseManifestEngine(manifestJson: String): SourceEngine? = runCatching {
        jsonMapper.readTree(manifestJson).get("engine")
            ?.takeIf { it.isTextual }?.asText()
            ?.let { SourceEngine.fromSerialName(it) }
    }.getOrNull()

    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()

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
                log("sync ${entry.id}: index v${entry.version} < active v$current — refused")
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
            is ManifestResult.Invalid -> {
                // Incompatible-but-signed manifests leave a marker (F-review):
                // without a record the upgrade reconciler has nothing to revive
                // (a daily re-download of the declined candidate is the price —
                // small manifests, bounded by the parser cap). Only written when
                // no record exists — a serving record is never touched by a
                // declined candidate.
                if (v.reason == ManifestInvalidReason.INCOMPATIBLE && indexStore.get(entry.id) == null) {
                    indexStore.put(
                        com.exapps.mangaworld.core.source.plugins.PluginIndexRecord(
                            id = entry.id,
                            activeVersion = null,
                            previousVersion = null,
                            origin = PluginOrigin.OFFICIAL,
                            status = PluginStatus.INCOMPATIBLE,
                            manifestJson = manifestBytes.toString(Charsets.UTF_8)
                        )
                    )
                }
                return EntryOutcome.Rejected("${v.reason}: ${v.message.take(120)}")
            }
            is ManifestResult.Valid -> v
        }
        // Defense in depth: the manifest must bind to the hint that found it.
        if (!PluginStorage.authorizeInstall(entry, valid)) {
            return EntryOutcome.Rejected("index/manifest id+version mismatch")
        }
        val manifest = valid.manifest
        if (PluginDistribution.kindEngineSkew(entry.kind, manifest.engine)) {
            log("sync ${entry.id}: index kind '${entry.kind}' vs engine '${manifest.engine.serialName}' — manifest wins")
        }
        if (manifest.engine in policy.disabledEngines) {
            return EntryOutcome.Rejected("engine ${manifest.engine.serialName} disabled by kill-switch")
        }
        // Script payloads download BEFORE any hold so every held record is
        // approvable from disk later (v9.1.0): the manifest URL that found this
        // candidate is gone by approval time. Bytes are hash-verified here and
        // re-checked at persist + load (defense in depth).
        var scriptBytes: ByteArray? = null
        if (manifest.engine == SourceEngine.SCRIPT) {
            when (val s = downloadScript(entry, indexUrl, indexHost, allowInsecure, manifest)) {
                is ScriptDownload.Ready -> scriptBytes = s.bytes
                is ScriptDownload.Terminal -> return s.outcome
            }
        }
        if (manifest.requiresPermission) {
            // Never auto-install consent-gated payloads: persist verified bytes
            // for review, keep serving the current version.
            persistQuietly(baseDir, entry, manifest, manifestBytes, scriptBytes = scriptBytes)
            return EntryOutcome.HeldForConsent
        }
        val builtin = registry.pluginFor(entry.id)
        if (builtin != null) {
            return syncOverride(entry, record, builtin, manifest, manifestBytes, postSmoke, baseDir)
        }
        // New id: pair a runner (Phase 3). Engines without a generic runner
        // (ASTRO/API/CUSTOM) keep the 2B behavior: verified payload retained
        // DISABLED, never registered — no phantom sources, no shadowing.
        val scraper = if (manifest.engine == SourceEngine.SCRIPT) {
            when (val r = runnerFactory.createScript(manifest, scriptBytes)) {
                null -> null
                else -> r.getOrElse {
                    return EntryOutcome.Rejected("script build: ${it.message?.take(120)}")
                }
            }
        } else {
            runnerFactory.createDescriptor(manifest)
        }
        if (scraper == null) {
            persistQuietly(baseDir, entry, manifest, manifestBytes, enabled = false)
            log("sync ${entry.id}: no runner for ${manifest.engine.serialName} — payload retained, registration deferred")
            return EntryOutcome.NewSourceDeferred
        }
        // Explicit type: smart-cast does not survive capture into the registry
        // plugin below, so bind the non-null runner once, by name.
        val runner: MangaScraper = scraper
        when (
            val persisted = pluginStore.persistVerified(
                baseDir = baseDir,
                id = entry.id,
                manifest = manifest,
                manifestBytes = manifestBytes,
                origin = PluginOrigin.OFFICIAL,
                scriptBytes = scriptBytes
            )
        ) {
            is PluginStore.InstallResult.Rejected ->
                return EntryOutcome.Rejected("${persisted.reason}: ${persisted.message.take(120)}")
            is PluginStore.InstallResult.Installed -> Unit
        }
        val plugin = object : SourcePlugin {
            override val descriptor: PluginManifest = manifest
            override val display = runnerFactory.remoteDisplay()
            override val scraper = runner
        }
        val outcome = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)
        if (outcome is SourceRegistry.RegisterOutcome.Refused) {
            return EntryOutcome.Rejected(outcome.reason)
        }
        val expanded = (outcome as? SourceRegistry.RegisterOutcome.Superseded)?.hostsExpanded == true
        // Fresh arrivals honor enabledByDefault exactly once (first install);
        // every later update retains the serving state — an explicit DISABLED
        // survives even a flag flip to true (F-review: the alternative silently
        // re-enables sources the user opted out of).
        val enable = record?.status == PluginStatus.ENABLED ||
            (record == null && manifest.enabledByDefault)
        if (enable && record?.status != PluginStatus.ENABLED) {
            runCatching { settingsRepo.toggleSource(entry.id, true) }
        }
        indexStore.get(entry.id)?.let { rec ->
            indexStore.put(rec.copy(status = if (enable) PluginStatus.ENABLED else PluginStatus.DISABLED))
        }
        if (!runPostSmoke(plugin, postSmoke)) {
            // New ids have no previous version to roll back TO (rollback() is a
            // no-op without one, which would wedge the record at INSTALLED and
            // read UpToDate forever). Remove the record instead: the next sweep
            // re-runs the full flow and self-heals when the payload is fixed.
            // Orphaned bytes are quota-collected (no record protects them).
            registry.clearOverride(entry.id)
            runCatching { indexStore.remove(entry.id) }
            log("sync ${entry.id}: post-activation smoke failed — rolled back")
            return EntryOutcome.RolledBack
        }
        return EntryOutcome.Updated(hostsExpanded = expanded)
    }

    /**
     * Override path: metadata supersede while the builtin scraper keeps serving
     * (behavior-identical by construction — same guarantee as the 2A pilots).
     * An engine change rewires what the metadata CLAIMS the scraper does, so it
     * is held for explicit consent instead of serving a mismatched pair (same
     * rule as `BundledPluginLoader.pilotSmoke`).
     */
    private suspend fun syncOverride(
        entry: PluginIndexEntry,
        record: com.exapps.mangaworld.core.source.plugins.PluginIndexRecord?,
        builtin: SourcePlugin,
        manifest: PluginManifest,
        manifestBytes: ByteArray,
        postSmoke: PostSmoke,
        baseDir: File
    ): EntryOutcome {
        if (manifest.engine != builtin.descriptor.engine) {
            persistQuietly(baseDir, entry, manifest, manifestBytes)
            log(
                "sync ${entry.id}: engine ${builtin.descriptor.engine.serialName} → " +
                    "${manifest.engine.serialName} held for consent"
            )
            return EntryOutcome.HeldForConsent
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
            log("sync ${entry.id}: official update expands host set — notice")
        }
        // Updates retain serving state: only an explicit DISABLED survives.
        val enable = record?.status != PluginStatus.DISABLED
        indexStore.get(entry.id)?.let { rec ->
            indexStore.put(rec.copy(status = if (enable) PluginStatus.ENABLED else PluginStatus.DISABLED))
        }
        if (!runPostSmoke(plugin, postSmoke)) {
            // Previous verified version retained by construction: roll the
            // pointer back so the next sweep retries the candidate instead of
            // reading UpToDate on a failed version forever (F-review: the old
            // registry-only rollback stranded the pointer). rollback() also
            // gives PluginStore.rollback its first production caller.
            registry.clearOverride(entry.id)
            runCatching { pluginStore.rollback(entry.id) }
            log("sync ${entry.id}: post-activation smoke failed — rolled back")
            return EntryOutcome.RolledBack
        }
        return EntryOutcome.Updated(hostsExpanded = expanded)
    }

    /** Script sibling download + hash pin (defense in depth: loader re-checks). */
    private sealed interface ScriptDownload {
        data class Ready(val bytes: ByteArray) : ScriptDownload {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Ready) return false
                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class Terminal(val outcome: EntryOutcome) : ScriptDownload
    }

    private suspend fun downloadScript(
        entry: PluginIndexEntry,
        indexUrl: String,
        indexHost: String,
        allowInsecure: Boolean,
        manifest: PluginManifest
    ): ScriptDownload {
        val scriptUrl = ScriptPluginLoader.siblingScriptUrl(entry.manifestUrl)
            ?: return ScriptDownload.Terminal(EntryOutcome.Rejected("no script url"))
        // Same-dir sibling: the same-host rule doubles as the fetch allow-list.
        PluginDistribution.checkManifestUrl(scriptUrl, indexUrl, allowInsecure)?.let {
            return ScriptDownload.Terminal(EntryOutcome.Rejected("script url: $it"))
        }
        val bytes = try {
            fetcher.get(
                url = scriptUrl,
                allowedHosts = setOf(indexHost),
                maxBytes = ScriptContract.SCRIPT_MAX_BYTES.toLong()
            ).body
        } catch (e: PluginFetcher.FetchFailure) {
            return ScriptDownload.Terminal(EntryOutcome.Failed("script fetch: ${e.message}"))
        }
        if (bytes.isEmpty()) {
            return ScriptDownload.Terminal(EntryOutcome.Rejected("empty script"))
        }
        val expected = manifest.scriptSha256
        if (expected == null ||
            !ScriptPluginLoader.sha256Hex(bytes).equals(expected, ignoreCase = true)
        ) {
            return ScriptDownload.Terminal(EntryOutcome.Rejected("script hash mismatch"))
        }
        return ScriptDownload.Ready(bytes)
    }

    /** Verified persist without activation (consent holds, future runners). */
    private suspend fun persistQuietly(
        baseDir: File,
        entry: PluginIndexEntry,
        manifest: PluginManifest,
        manifestBytes: ByteArray,
        enabled: Boolean = false,
        scriptBytes: ByteArray? = null
    ) {
        when (pluginStore.persistVerified(baseDir, entry.id, manifest, manifestBytes, PluginOrigin.OFFICIAL, scriptBytes)) {
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

    // ─── Explicit approval (v9.1.0 consent surfaces) ─────────────────────────

    sealed interface ApproveOutcome {
        data object Approved : ApproveOutcome
        /** Still held: no runner, refused registration, or revoked meanwhile. */
        data class Held(val reason: String) : ApproveOutcome
        /** Terminal for this attempt: unknown id, unreadable payload, failed gates. */
        data class Failed(val reason: String) : ApproveOutcome
        data object Unknown : ApproveOutcome
    }

    /**
     * Approves a held record (consent hold, engine-change hold, deferral,
     * fresh opt-out): re-verifies the on-disk bytes against CURRENT keys and
     * policy, pairs the runner exactly like the sync paths, registers,
     * enables, and post-smokes. Rollback on smoke failure mirrors sync
     * (override → pointer rollback; new id → record removal).
     *
     * Mirrors sync pairing deliberately: same-engine overrides reuse the
     * builtin scraper; an engine change the user just consented to pairs the
     * new runner; new ids pair runners with the remote fallback display.
     */
    @Suppress("LongParameterList")
    suspend fun approveHeld(
        id: String,
        baseDir: File,
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        killSwitchJson: String? = null,
        postSmoke: PostSmoke = PostSmoke.Network()
    ): ApproveOutcome = withContext(io) {
        val record = indexStore.get(id) ?: return@withContext ApproveOutcome.Unknown
        val version = record.activeVersion
            ?: return@withContext ApproveOutcome.Failed("no payload")
        val manifestFile = File(
            com.exapps.mangaworld.core.source.plugins.PluginStorage.versionDir(baseDir.path, id, version),
            "plugin.json"
        )
        val manifestBytes = if (manifestFile.isFile) {
            runCatching { manifestFile.readBytes() }.getOrNull()
        } else null ?: return@withContext ApproveOutcome.Failed("payload missing")
        val policy = PluginKillSwitch.parse(killSwitchJson)
        if (PluginKillSwitch.isRevoked(policy, id, version)) {
            applyKillSwitch(
                PluginKillSwitch.Policy(
                    revoked = listOf(PluginKillSwitch.Revocation(id, version))
                )
            )
            return@withContext ApproveOutcome.Held("revoked")
        }
        val manifest = when (
            val v = pluginStore.verify(
                manifestBytes = manifestBytes,
                trustedKeys = trustedKeys,
                host = host,
                enforceFreshness = true
            )
        ) {
            is ManifestResult.Invalid -> return@withContext ApproveOutcome.Failed("${v.reason}: ${v.message.take(120)}")
            is ManifestResult.Valid -> v.manifest
        }
        if (manifest.engine in policy.disabledEngines) {
            return@withContext ApproveOutcome.Held("engine ${manifest.engine.serialName} disabled")
        }
        var scriptBytes: ByteArray? = null
        if (manifest.engine == SourceEngine.SCRIPT) {
            val js = File(manifestFile.parent, "source.js").takeIf { it.isFile }
                ?.let { runCatching { it.readBytes() }.getOrNull() }
            if (js == null || js.isEmpty()) {
                return@withContext ApproveOutcome.Failed("script payload missing — re-sync")
            }
            val expected = manifest.scriptSha256
            if (expected == null ||
                !ScriptPluginLoader.sha256Hex(js).equals(expected, ignoreCase = true)
            ) {
                return@withContext ApproveOutcome.Failed("script hash mismatch")
            }
            scriptBytes = js
        }
        val builtin = registry.pluginFor(id)
        val scraper: MangaScraper
        val display: com.exapps.mangaworld.core.source.plugins.SourceDisplay
        if (builtin != null && manifest.engine == builtin.descriptor.engine) {
            scraper = builtin.scraper
            display = builtin.display
        } else {
            val runner = if (manifest.engine == SourceEngine.SCRIPT) {
                when (val r = runnerFactory.createScript(manifest, scriptBytes)) {
                    null -> null
                    else -> r.getOrElse {
                        return@withContext ApproveOutcome.Failed("script build: ${it.message?.take(120)}")
                    }
                }
            } else {
                runnerFactory.createDescriptor(manifest)
            } ?: return@withContext ApproveOutcome.Held("no runner for ${manifest.engine.serialName}")
            scraper = runner
            display = builtin?.display ?: runnerFactory.remoteDisplay()
        }
        when (
            val persisted = pluginStore.persistVerified(
                baseDir = baseDir,
                id = id,
                manifest = manifest,
                manifestBytes = manifestBytes,
                origin = PluginOrigin.OFFICIAL,
                scriptBytes = scriptBytes
            )
        ) {
            is PluginStore.InstallResult.Rejected ->
                return@withContext ApproveOutcome.Failed("${persisted.reason}: ${persisted.message.take(120)}")
            is PluginStore.InstallResult.Installed -> Unit
        }
        val plugin = object : SourcePlugin {
            override val descriptor: PluginManifest = manifest
            override val display = display
            override val scraper = scraper
        }
        when (val outcome = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)) {
            is SourceRegistry.RegisterOutcome.Refused ->
                return@withContext ApproveOutcome.Held(outcome.reason)
            else -> Unit
        }
        runCatching { settingsRepo.toggleSource(id, true) }
        indexStore.get(id)?.let { rec ->
            indexStore.put(rec.copy(status = PluginStatus.ENABLED))
        }
        if (!runPostSmoke(plugin, postSmoke)) {
            if (builtin != null) {
                registry.clearOverride(id)
                runCatching { pluginStore.rollback(id) }
            } else {
                registry.clearOverride(id)
                runCatching { indexStore.remove(id) }
            }
            log("approve $id: post-activation smoke failed — rolled back")
            return@withContext ApproveOutcome.Failed("post-activation smoke failed")
        }
        ApproveOutcome.Approved
    }

    companion object {
        private const val TAG = "PluginSync"
        const val ManifestParserMaxBytes: Int =
            com.exapps.mangaworld.core.source.plugins.ManifestParser.MAX_MANIFEST_BYTES
    }
}
