package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.sync.EtagStore
import com.exapps.mangaworld.core.source.sync.OkHttpPluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.core.source.sync.PostSmoke
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import com.exapps.mangaworld.core.source.plugins.PluginRunnerFactory
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Phase 2B accept drills against fakes (no network, no Android): pilot update
 * with a domain move and no APK, downgrade refusal + rollback, tampered/
 * forged/incompatible/stale rejections, consent holds, kill-switches, ETag
 * short-circuit, and kind-skew tolerance. Transport gates live in
 * [PluginHttpFetcherTest]; trust age and rotation in [PluginTrustTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeIndex : PluginIndexStore {
        val map = mutableMapOf<String, PluginIndexRecord>()
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private class FakeEtag(var value: String? = null) : EtagStore {
        override fun get(): String? = value
        override fun set(etag: String?) {
            value = etag
        }
        var unresolvedIds: Set<String> = emptySet()
        override fun getUnresolved(): Set<String> = unresolvedIds
        override fun setUnresolved(ids: Set<String>) {
            unresolvedIds = ids
        }
    }

    private class FakeFetcher(
        val bodies: MutableMap<String, ByteArray>,
        var indexEtag: String? = "E1",
        val requests: MutableList<String> = mutableListOf(),
        var manifestRequests: Int = 0
    ) : PluginFetcher {
        override suspend fun get(
            url: String,
            allowedHosts: Set<String>,
            maxBytes: Long,
            headers: Map<String, String>
        ): PluginFetcher.FetchResult {
            requests += url
            if (url.endsWith("index.json")) {
                if (indexEtag != null && headers["If-None-Match"] == indexEtag) {
                    throw OkHttpPluginFetcher.NotModified()
                }
                return PluginFetcher.FetchResult(
                    bodies[url] ?: throw PluginFetcher.FetchFailure.Http(404, url),
                    url, indexEtag
                )
            }
            manifestRequests++
            return PluginFetcher.FetchResult(
                bodies[url] ?: throw PluginFetcher.FetchFailure.Http(404, url),
                url, null
            )
        }
    }

    private val kp = PluginTestFixtures.generateKeyPair()
    private val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))
    private val host = HostCapabilities(
        appVersion = "8.10.0",
        supportedEngines = SourceEngine.entries.associateWith { 1..99 },
        supportedBridgeApi = 9
    )

    private fun nowIso(): String = Instant.now().toString()

    private fun manifestBytes(
        id: String,
        version: Int,
        mutate: (ObjectNode) -> Unit = {}
    ): ByteArray {
        val node = PluginTestFixtures.manifestTree {
            it.put("id", id)
            it.put("version", version)
            it.put("engineApi", 1)
            it.put("issuedAt", nowIso())
            mutate(it)
        }
        return PluginTestFixtures.signManifest(node, "k1", kp.private)
    }

    private fun indexJson(vararg entries: Triple<String, Int, String>): ByteArray {
        val body = entries.joinToString(",") { (id, version, url) ->
            """{"id":"$id","version":$version,"kind":"descriptor","minAppVersion":"8.9.0","manifestUrl":"$url"}"""
        }
        return """{"schemaVersion":1,"updatedAt":"${nowIso()}","entries":[$body]}"""
            .toByteArray(Charsets.UTF_8)
    }

    // Phase 3: the engine pairs runners + honors enabledByDefault. Existing drills
    // use a runner-less factory (historical deferral behavior) and a no-op settings.
    private fun nullRunners(): PluginRunnerFactory = mockk {
        every { createDescriptor(any()) } returns null
    }

    /**
     * A factory that CAN pair a runner — needed by every path that builds one from
     * bytes. Relaxed, because install also reads `remoteDisplay()`; a strict mock
     * fails with "no answer found" instead of testing the path under test.
     */
    private fun scrapingRunners(): PluginRunnerFactory =
        mockk<PluginRunnerFactory>(relaxed = true).also { factory ->
        every { factory.createDescriptor(any()) } returns
            mockk<com.exapps.mangaworld.core.data.remote.scraper.MangaScraper>(relaxed = true)
        // createScript returns Result<MangaScraper>? — a success, not a bare scraper.
        every { factory.createScript(any(), any()) } returns Result.success(
            mockk<com.exapps.mangaworld.core.data.remote.scraper.MangaScraper>(relaxed = true)
        )
    }

    private fun silentSettings(): SettingsRepository = mockk(relaxed = true)

    private data class Harness(
        val engine: PluginSyncEngine,
        val index: FakeIndex,
        val fetcher: FakeFetcher,
        val registry: com.exapps.mangaworld.core.source.plugins.SourceRegistry,
        val trust: Map<String, ByteArray>,
        val host: HostCapabilities,
        val baseDir: java.io.File,
        /** Exposed so tests can clear the 304 shortcut and force a full fetch. */
        val etags: FakeEtag,
        /** Collects engine log notices (host-expansion, skew, rollback). */
        val logs: MutableList<String> = mutableListOf(),
        /** Gate 0: records what the fleet would observe for this sweep. */
        val telemetry: RecordingPluginTelemetry = RecordingPluginTelemetry()
    ) {
        suspend fun sync(
            kill: String? = null,
            smoke: PostSmoke = PostSmoke.Custom({ true })
        ): PluginSyncEngine.SyncResult = engine.sync(
            trustedKeys = trust,
            host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            killSwitchJson = kill,
            postSmoke = smoke,
            baseDir = baseDir
        )
    }

    private fun harness(
        bodies: MutableMap<String, ByteArray>,
        registryIds: Array<String> = arrayOf("hijala", "lavascans"),
        index: FakeIndex = FakeIndex(),
        etags: FakeEtag = FakeEtag(),
        fetcher: FakeFetcher = FakeFetcher(bodies),
        runners: PluginRunnerFactory = nullRunners()
    ): Harness {
        val registry = SourceUiTestFixtures.registry(*registryIds)
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val telemetry = RecordingPluginTelemetry()
        val engine = PluginSyncEngine(index, store, registry, fetcher, etags, runners, silentSettings(), telemetry, kotlinx.coroutines.Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        engine.log = { logs += it }
        return Harness(engine, index, fetcher, registry, trust, host, tmp.root, etags, logs, telemetry)
    }

    @Test
    fun reDrivesUnresolvedRecordInsteadOfReportingUpToDate() = runTest {
        // v9.2.5 (audit B6): a record claiming an active version while NOTHING is
        // registered for that id is the UNRESOLVED posture (payload pruned or
        // torn). The sweep used to answer UpToDate there, which made the Sources
        // "re-sync" button a dead end — the user asked to repair and the sweep
        // reported there was nothing to do.
        val manifestUrl = "https://cdn.example/plugins/newbie/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newbie", 1, manifestUrl)),
            manifestUrl to manifestBytes("newbie", 1)
        )
        val index = FakeIndex()
        // Record claims v1 (so version comparison says "same"), but the registry
        // has no plugin for the id — exactly the unresolved case.
        index.put(PluginIndexRecord("newbie", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val h = harness(bodies, registryIds = arrayOf("hijala", "lavascans"), index = index)
        val result = h.engine.sync(
            trustedKeys = h.trust, host = h.host,
            indexUrl = "https://cdn.example/plugins/index.json",
            baseDir = h.baseDir
        )
        val outcome = result.outcomes["newbie"]
        assertTrue(
            "expected a re-drive, got $outcome",
            outcome != null && outcome !is PluginSyncEngine.EntryOutcome.UpToDate
        )
        assertTrue(
            "the re-drive must be logged so logcat names the repair",
            h.logs.any { it.contains("re-driving") }
        )
    }

    @Test
    fun malformedKillSwitchAbortsTheSweep() = runTest {
        // v9.2.5 (audit B11): a kill-switch payload that exists but cannot be
        // parsed used to collapse to "no policy" — silently disabling every
        // revocation and engine kill. A sweep must refuse to act instead.
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        runCatching {
            h.engine.sync(
                trustedKeys = h.trust, host = h.host,
                indexUrl = "https://cdn.example/plugins/index.json",
                killSwitchJson = "{not json at all",
                baseDir = h.baseDir
            )
        }.onSuccess { fail("a malformed kill-switch must abort the sweep") }
    }

    @Test
    fun malformedKillSwitchHoldsApproval() = runTest {
        // Same rule on the approval lane: never activate under an unreadable
        // safety policy.
        val h = harness(mutableMapOf())
        val outcome = h.engine.approveHeld(
            id = "hijala",
            baseDir = h.baseDir,
            trustedKeys = h.trust,
            host = h.host,
            killSwitchJson = "[[[",
            postSmoke = PostSmoke.Disabled
        )
        assertTrue(
            "approval must hold, got $outcome",
            outcome is PluginSyncEngine.ApproveOutcome.Held
        )
    }

    @Test
    fun persistRejectionIsLoggedNotSwallowed() = runTest {
        // v9.2.5 (audit B10): persistQuietly discarded InstallResult.Rejected, so
        // a payload that never landed still produced a clean-looking row and left
        // no trace at all — the next boot then reported NoPayload with nothing to
        // connect it back to.
        val manifestUrl = "https://cdn.example/plugins/newsite/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newsite", 1, manifestUrl)),
            manifestUrl to manifestBytes("newsite", 1)
        )
        // Pre-seed v1 with DIFFERENT validly-signed bytes → immutability refusal.
        val dir = File(
            com.exapps.mangaworld.core.source.plugins.PluginStorage
                .versionDir(tmp.root.path, "newsite", 1)
        )
        dir.mkdirs()
        File(dir, "plugin.json").writeBytes(
            manifestBytes("newsite", 1) { it.put("timeoutMs", 31000) }
        )
        val h = harness(bodies, registryIds = arrayOf("hijala", "lavascans"))
        h.engine.sync(
            trustedKeys = h.trust, host = h.host,
            indexUrl = "https://cdn.example/plugins/index.json",
            baseDir = h.baseDir
        )
        assertTrue(
            "expected the persist rejection to be logged, got: ${h.logs}",
            h.logs.any { it.contains("persist rejected") }
        )
    }

    // ─── Drills ─────────────────────────────────────────────────────────────

    @Test
    fun updateAppliesWithDomainMoveAndNoApk() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2) {
                it.put("baseUrl", "https://hijala-moved.example")
                it.putArray("allowedHosts")
                    .add("starzmanga.com").add("cdn.starzmanga.com").add("hijala-moved.example")
            }
        )
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val fetcher = FakeFetcher(bodies)
        val engine = PluginSyncEngine(
            index, store, registry, fetcher, FakeEtag(), nullRunners(), silentSettings(), RecordingPluginTelemetry(), kotlinx.coroutines.Dispatchers.Unconfined
        )
        val logs = mutableListOf<String>()
        engine.log = { logs += it }
        val scraperBefore = registry.scraperFor("hijala")
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertEquals(PluginSyncEngine.EntryOutcome.Updated(hostsExpanded = true), result.outcomes["hijala"])
        // Plan §8.9 non-blocking notice is logged and reported.
        assertTrue(logs.any { it.contains("expands host set") })
        // Domain moved with no APK: descriptor carries the new base, scraper untouched.
        assertEquals("https://hijala-moved.example", registry.descriptorFor("hijala")!!.baseUrl)
        assertTrue(registry.scraperFor("hijala") === scraperBefore)
        assertTrue(registry.isOverridden("hijala"))
        val record = index.get("hijala")!!
        assertEquals(2, record.activeVersion)
        assertEquals(1, record.previousVersion)
        assertEquals(PluginStatus.ENABLED, record.status)
        // v1 was builtin-served (no payload bytes); only v2 has a version dir.
        assertFalse(File(tmp.root, "hijala/versions/1").exists())
        assertTrue(File(tmp.root, "hijala/versions/2/plugin.json").isFile)
    }

    @Test
    fun tamperedManifestRejectedActiveUntouched() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val good = manifestBytes("hijala", 2)
        good[good.size / 2] = (good[good.size / 2].toInt() xor 0xFF).toByte()
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to good
        )
        val h = harness(bodies)
        val result = h.sync()
        assertTrue(result.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Rejected)
        // Builtin descriptor still served; nothing recorded.
        assertEquals("https://hijala.example", h.registry.descriptorFor("hijala")!!.baseUrl)
        assertTrue(h.index.get("hijala") == null)
    }

    @Test
    fun terminalOutcomeLinesCarryTheGateReason() = runTest {
        // v9.1.7: a bare `-> Rejected` code is undiagnosable on fleet builds
        // (the 9.1.6 manonga case) — the reason must ride the log line.
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val good = manifestBytes("hijala", 2)
        good[good.size / 2] = (good[good.size / 2].toInt() xor 0xFF).toByte()
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to good
        )
        val h = harness(bodies)
        h.sync()
        val line = h.logs.firstOrNull { it.contains("sync hijala -> Rejected") }
        assertTrue("missing outcome line: ${h.logs}", line != null)
        assertTrue("reason missing: $line", line!!.length > "sync hijala -> Rejected".length)
    }

    @Test
    fun forgedIndexBumpRejectedByIdVersionBind() = runTest {
        // Index claims v99; the only signed bytes in the world are v2.
        val manifestUrl = "https://cdn.example/plugins/hijala/v99/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 99, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        val result = h.sync()
        val outcome = result.outcomes["hijala"]!!
        assertTrue(outcome is PluginSyncEngine.EntryOutcome.Rejected)
        assertTrue((outcome as PluginSyncEngine.EntryOutcome.Rejected).reason.contains("mismatch"))
    }

    @Test
    fun incompatibleAndStaleRejected() = runTest {
        val futureUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val staleUrl = "https://cdn.example/plugins/lavascans/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(
                Triple("hijala", 2, futureUrl),
                Triple("lavascans", 2, staleUrl)
            ),
            futureUrl to manifestBytes("hijala", 2) { it.put("engineApi", 100) },
            staleUrl to manifestBytes("lavascans", 2) { it.put("issuedAt", "2020-01-01T00:00:00Z") }
        )
        val h = harness(bodies)
        val result = h.sync()
        assertTrue(result.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Rejected)
        assertTrue(result.outcomes["lavascans"] is PluginSyncEngine.EntryOutcome.Rejected)
    }

    @Test
    fun downgradeRefusedActiveStays() = runTest {
        val v2Url = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val v1Url = "https://cdn.example/plugins/hijala/v1/plugin.json"
        val v2 = manifestBytes("hijala", 2) { it.put("baseUrl", "https://hijala-v2.example") }
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, v2Url)),
            v2Url to v2
        )
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val fetcher = FakeFetcher(bodies)
        val etags = FakeEtag()
        val engine = PluginSyncEngine(
            index, store, registry, fetcher, etags, nullRunners(), silentSettings(), RecordingPluginTelemetry(), kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        suspend fun runSync() = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(runSync().outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        // Index rewinds to v1: refused, v2 keeps serving.
        bodies["https://cdn.example/plugins/index.json"] = indexJson(Triple("hijala", 1, v1Url))
        bodies[v1Url] = manifestBytes("hijala", 1)
        // Drop the stored validator so the rewound index is actually re-read
        // (otherwise 304 ends the sync before the downgrade is evaluated).
        etags.set(null)
        val second = runSync()
        assertEquals(PluginSyncEngine.EntryOutcome.DowngradeRefused, second.outcomes["hijala"])
        assertEquals("https://hijala-v2.example", registry.descriptorFor("hijala")!!.baseUrl)
        assertEquals(2, index.get("hijala")!!.activeVersion)
    }

    @Test
    fun postSmokeFailureRollsBackPointer() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(), nullRunners(), silentSettings(), RecordingPluginTelemetry(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ false }), baseDir = tmp.root
        )
        assertEquals(PluginSyncEngine.EntryOutcome.RolledBack, result.outcomes["hijala"])
        // Builtin resumes via override removal; the POINTER rolls back to v1
        // (status INSTALLED) so the next sweep retries instead of wedging on
        // UpToDate — failed v2 bytes stay staged for forensics/quota.
        assertFalse(registry.isOverridden("hijala"))
        assertEquals(1, index.get("hijala")!!.activeVersion)
        assertEquals(PluginStatus.INSTALLED, index.get("hijala")!!.status)
        assertTrue(File(tmp.root, "hijala/versions/2/plugin.json").isFile)
    }

    @Test
    fun killSwitchRevokesOverride() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val etags = FakeEtag()
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), etags, nullRunners(), silentSettings(), RecordingPluginTelemetry(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        suspend fun runSync(kill: String?) = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            killSwitchJson = kill,
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(runSync(null).outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        assertTrue(registry.isOverridden("hijala"))
        // Re-read the index under the kill policy (no 304 shortcut).
        etags.set(null)
        val killed = runSync("""{"revoked":[{"id":"hijala","version":2}]}""")
        assertEquals(PluginSyncEngine.EntryOutcome.Revoked, killed.outcomes["hijala"])
        assertTrue(killed.revocationsApplied.contains("hijala"))
        assertFalse(registry.isOverridden("hijala"))
        assertEquals(PluginStatus.REVOKED, index.get("hijala")!!.status)
    }

    @Test
    fun permissionGatedUpdateHeldForConsent() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2) { it.put("requiresPermission", true) }
        )
        val h = harness(bodies)
        val result = h.sync()
        assertEquals(PluginSyncEngine.EntryOutcome.HeldForConsent, result.outcomes["hijala"])
        // Current (builtin) version keeps serving; payload retained DISABLED.
        assertFalse(h.registry.isOverridden("hijala"))
        assertEquals(PluginStatus.DISABLED, h.index.get("hijala")!!.status)
        assertTrue(File(tmp.root, "hijala/versions/2/plugin.json").isFile)
    }

    @Test
    fun newSourceWithoutRunnerDeferredNeverRegistered() = runTest {
        val manifestUrl = "https://cdn.example/plugins/newsite/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newsite", 1, manifestUrl)),
            manifestUrl to manifestBytes("newsite", 1)
        )
        val index = FakeIndex()
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(), nullRunners(), silentSettings(), RecordingPluginTelemetry(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertEquals(PluginSyncEngine.EntryOutcome.NewSourceDeferred, result.outcomes["newsite"])
        assertFalse(registry.isKnown("newsite"))
        assertEquals(PluginStatus.DISABLED, index.get("newsite")!!.status)
    }

    @Test
    fun etagShortCircuitsUnchangedIndex() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val fetcher = FakeFetcher(bodies)
        val etags = FakeEtag()
        val index = FakeIndex()
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(
            index, store, registry, fetcher, etags, nullRunners(), silentSettings(), RecordingPluginTelemetry(), kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        suspend fun runSync() = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        val first = runSync()
        assertTrue(first.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        assertEquals("E1", etags.get())
        val before = fetcher.manifestRequests
        val second = runSync()
        assertTrue(second.indexNotModified)
        assertEquals(before, fetcher.manifestRequests)
    }

    @Test
    fun failedEntriesForceFullRefetchPast304() = runTest {
        // v9.1.5 (on-device canary went dark): a sweep that fails an entry
        // stores the index ETag anyway, so every later sweep 304s with EMPTY
        // outcomes — the failure is never retried nor re-logged until the
        // index changes. Failed/RolledBack ids must force a full fetch.
        val manifestUrl = "https://cdn.example/plugins/ghost/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("ghost", 1, manifestUrl))
            // No manifest body: the fetch 404s → Failed (not an escape).
        )
        val etags = FakeEtag()
        val h = harness(bodies, etags = etags)
        val first = h.sync()
        assertTrue(first.outcomes["ghost"] is PluginSyncEngine.EntryOutcome.Failed)
        assertEquals(setOf("ghost"), etags.unresolvedIds)
        val indexHitsBefore = h.fetcher.requests.count { it.endsWith("index.json") }
        val second = h.sync()
        assertTrue(!second.indexNotModified)
        assertTrue(second.outcomes["ghost"] is PluginSyncEngine.EntryOutcome.Failed)
        assertEquals(indexHitsBefore + 1, h.fetcher.requests.count { it.endsWith("index.json") })
        assertTrue(h.logs.any { it.contains("forcing full fetch") && it.contains("ghost") })
    }

    @Test
    fun syncEmitsFleetReport() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        val result = h.sync()
        assertTrue(result.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        // Gate 0: exactly one aggregate report per sweep, shaped for the fleet view.
        val report = h.telemetry.syncs.single()
        assertEquals(false, report.indexNotModified)
        assertEquals(1, report.outcomeCounts["Updated"])
        assertTrue(report.failedIds.isEmpty())
        assertTrue(report.rejectedIds.isEmpty())
        assertTrue(report.durationMs >= 0)
        assertEquals(result.evictedVersions, report.evictedVersions)
    }

    @Test
    fun sweepLogsPerEntryOutcomesForLogcat() = runTest {        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        h.sync()
        // v9.1.2: non-trivial outcomes get their own line; the summary always lands.
        assertTrue(h.logs.any { it.contains("sync hijala -> Updated") })
        assertTrue(h.logs.any { it.contains("sync done:") && it.contains("notModified=false") })
    }

    @Test
    fun abortedSweepLogsCauseForLogcat() = runTest {
        // Empty bodies: even the index fetch 404s, so the sweep aborts before
        // any outcome exists. v9.1.3: the abort still logs class+message (and
        // still emits the SyncAborted telemetry report) — transport-level
        // failures must never be fully silent on-device again.
        val h = harness(mutableMapOf())
        try {
            h.sync()
            fail("expected abort")
        } catch (e: PluginFetcher.FetchFailure.Http) {
            // Expected: FakeFetcher 404s the index fetch.
        }
        assertTrue(h.logs.any { it.contains("sync aborted:") })
        val report = h.telemetry.syncs.single()
        assertEquals(1, report.outcomeCounts["SyncAborted"])
    }

    @Test
    fun notModifiedSweepEmitsHeartbeatReport() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        h.sync()
        h.telemetry.clear()
        // FakeFetcher answers 304 once the ETag is stored (see FakeFetcher).
        val second = h.sync()
        assertTrue(second.indexNotModified)
        // Heartbeat, not silence: an empty sweep still emits so death is
        // distinguishable from a quiet fleet.
        val report = h.telemetry.syncs.single()
        assertEquals(true, report.indexNotModified)
        assertTrue(report.outcomeCounts.isEmpty())
    }

    @Test
    fun engineKillRefusesCandidates() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        val result = h.sync(kill = """{"disabledEngines":["madara"]}""")
        assertTrue(result.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Rejected)
    }

    @Test
    fun disableAllCustomsUnregistersLabRemotes() = runTest {
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson()
        )
        val index = FakeIndex()
        val scraper: com.exapps.mangaworld.core.data.remote.scraper.MangaScraper =
            io.mockk.mockk(relaxed = true)
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val custom = object : com.exapps.mangaworld.core.source.plugins.SourcePlugin {
            override val descriptor = SourceUiTestFixtures.registry("customx")
                .descriptorFor("customx")!!
            override val display = com.exapps.mangaworld.core.source.plugins.SourceDisplay(0, 0)
            override val scraper = scraper
        }
        assertTrue(
            registry.registerVerified(
                custom,
                com.exapps.mangaworld.core.source.plugins.PluginOrigin.CUSTOM
            ) is SourceRegistry.RegisterOutcome.Installed
        )
        index.put(PluginIndexRecord("customx", 1, null, PluginOrigin.CUSTOM, PluginStatus.ENABLED, null))
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(), nullRunners(), silentSettings(), RecordingPluginTelemetry(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        engine.log = { } // JVM: android Log stubs throw
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            killSwitchJson = """{"disableAllCustoms":true}""",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(result.revocationsApplied.any { it.startsWith("customx") })
        assertTrue(registry.scraperFor("customx") == null)
        assertEquals(PluginStatus.DISABLED, index.get("customx")!!.status)
    }

    @Test
    fun kindSkewToleratedManifestWins() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val body = """{"schemaVersion":1,"updatedAt":"${nowIso()}","entries":[{"id":"hijala","version":2,"kind":"script","minAppVersion":"8.9.0","manifestUrl":"$manifestUrl"}]}"""
            .toByteArray(Charsets.UTF_8)
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to body,
            manifestUrl to manifestBytes("hijala", 2)
        )
        val h = harness(bodies)
        val result = h.sync()
        // Manifest (madara) wins over the stale "script" hint; update proceeds.
        assertTrue(result.outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        assertTrue(h.logs.any { it.contains("manifest wins") })
    }

    @Test
    fun engineKillRevokesActiveOverride() = runTest {
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson()
        )
        val index = FakeIndex()
        index.put(
            PluginIndexRecord(
                "hijala", 2, 1, PluginOrigin.OFFICIAL, PluginStatus.ENABLED,
                """{"engine":"madara"}"""
            )
        )
        val h = harness(bodies, index = index)
        // Serving override (as a previous sync would have installed it).
        val scraper: com.exapps.mangaworld.core.data.remote.scraper.MangaScraper =
            mockk(relaxed = true)
        val override = object : com.exapps.mangaworld.core.source.plugins.SourcePlugin {
            override val descriptor = h.registry.descriptorFor("hijala")!!
            override val display = com.exapps.mangaworld.core.source.plugins.SourceDisplay(0, 0)
            override val scraper = scraper
        }
        h.registry.registerVerified(override, PluginOrigin.OFFICIAL)
        assertTrue(h.registry.isOverridden("hijala"))
        // Engine kill applies to ACTIVES, not just candidates: UpToDate can no
        // longer shield a killed engine.
        val result = h.sync(kill = """{"disabledEngines":["madara"]}""")
        assertTrue(result.revocationsApplied.any { it.startsWith("hijala") })
        assertFalse(h.registry.isOverridden("hijala"))
        assertEquals(PluginStatus.REVOKED, h.index.get("hijala")!!.status)
    }

    @Test
    fun incompatibleCandidateLeavesMarkerRecord() = runTest {
        val manifestUrl = "https://cdn.example/plugins/futurex/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("futurex", 1, manifestUrl)),
            manifestUrl to manifestBytes("futurex", 1) { it.put("engineApi", 999) }
        )
        val h = harness(bodies)
        val result = h.sync()
        assertTrue(result.outcomes["futurex"] is PluginSyncEngine.EntryOutcome.Rejected)
        // Marker for the upgrade reconciler: no pointer, no registration.
        val rec = h.index.get("futurex")!!
        assertEquals(PluginStatus.INCOMPATIBLE, rec.status)
        assertEquals(null, rec.activeVersion)
        assertFalse(h.registry.isKnown("futurex"))
    }

    /**
     * Containment recovery leg (drill 3, 2026-10-18): once the kill policy is
     * removed, the next sweep must RE-DRIVE the install and serve again.
     *
     * It does, because the kill dropped the only runner, so the record has nothing
     * backing it and the `UpToDate` short-circuit can no longer shield it (v9.2.5 B6).
     * Modelled on a REMOTE id on purpose: for a builtin, `pluginFor` falls back to the
     * APK scraper, so the test would pass for the wrong reason. A runner factory that can
     * actually build one is required too — with `nullRunners()` any re-drive simply defers.
     */
    @Test
    fun liftedRevocationReDrivesAndResumesServing() = runTest {
        val manifestUrl = "https://cdn.example/plugins/newbie/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newbie", 1, manifestUrl)),
            manifestUrl to manifestBytes("newbie", 1)
        )
        val h = harness(
            bodies,
            registryIds = arrayOf("hijala", "lavascans"),
            runners = scrapingRunners()
        )
        val installed = h.sync()
        assertTrue(
            "install: expected Updated, got ${installed.outcomes["newbie"]}",
            installed.outcomes["newbie"] is PluginSyncEngine.EntryOutcome.Updated
        )
        assertNotNull("install must register a runner", h.registry.pluginFor("newbie"))

        // Containment: kill the id outright.
        h.etags.set(null)
        val killed = h.sync(kill = """{"revoked":[{"id":"newbie"}]}""")
        assertEquals(
            "kill: got ${killed.outcomes["newbie"]} revocations=${killed.revocationsApplied}",
            PluginSyncEngine.EntryOutcome.Revoked, killed.outcomes["newbie"]
        )
        assertEquals(PluginStatus.REVOKED, h.index.get("newbie")!!.status)
        assertNull("kill must drop the only runner", h.registry.pluginFor("newbie"))

        // Policy lifted (RC restored): must not leave the source permanently dead.
        h.etags.set(null)
        val recovered = h.sync(kill = null)
        assertTrue(
            "lifted revocation must re-drive and resume serving, got ${recovered.outcomes["newbie"]}",
            recovered.outcomes["newbie"] is PluginSyncEngine.EntryOutcome.Updated
        )
        assertNotNull("recovery must re-register a runner", h.registry.pluginFor("newbie"))
        // It comes back DISABLED, not serving: with the runner gone the record is
        // indistinguishable from a new arrival, so the new-id consent rule applies
        // again (syncEntry -> `existing == null` -> manifest.enabledByDefault).
        // Pinned deliberately — a revoked source must NOT silently resume serving.
        assertEquals(PluginStatus.DISABLED, h.index.get("newbie")!!.status)
    }


    /**
     * A lifted revocation must be re-driven WITHOUT a publisher action (drill 3).
     * `Revoked` is not in the unresolved set, so with an unchanged index the 304
     * short-circuit skipped the id entirely and the source stayed dead. Proves the
     * escape hatch: second sweep with no policy re-drives even behind a 304.
     */
    @Test
    fun liftedRevocationReDrivesDespiteUnchangedIndex() = runTest {
        val manifestUrl = "https://cdn.example/plugins/newbie/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newbie", 1, manifestUrl)),
            manifestUrl to manifestBytes("newbie", 1)
        )
        val h = harness(bodies, registryIds = arrayOf("hijala", "lavascans"), runners = scrapingRunners())
        assertTrue(h.sync().outcomes["newbie"] is PluginSyncEngine.EntryOutcome.Updated)

        h.etags.set(null)
        assertEquals(
            PluginSyncEngine.EntryOutcome.Revoked,
            h.sync(kill = """{"revoked":[{"id":"newbie"}]}""").outcomes["newbie"]
        )

        // No etag clearing here on purpose: the index is unchanged, so a 304 would
        // otherwise short-circuit the whole sweep and the id would never be revisited.
        val recovered = h.sync(kill = null)
        assertTrue(
            "a lifted revocation must re-drive past the 304, got ${recovered.outcomes["newbie"]}",
            recovered.outcomes["newbie"] is PluginSyncEngine.EntryOutcome.Updated
        )
        assertNotNull("recovery must re-register a runner", h.registry.pluginFor("newbie"))
    }

    /**
     * A version-scoped kill must NOT look lifted: otherwise every sweep re-drives a
     * still-forbidden payload forever.
     */
    @Test
    fun stillRevokedRecordIsNotMarkedUnresolved() = runTest {
        val manifestUrl = "https://cdn.example/plugins/newbie/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("newbie", 1, manifestUrl)),
            manifestUrl to manifestBytes("newbie", 1)
        )
        val h = harness(bodies, registryIds = arrayOf("hijala", "lavascans"), runners = scrapingRunners())
        assertTrue(h.sync().outcomes["newbie"] is PluginSyncEngine.EntryOutcome.Updated)
        h.etags.set(null)
        val kill = """{"revoked":[{"id":"newbie","version":1}]}"""
        assertEquals(
            PluginSyncEngine.EntryOutcome.Revoked, h.sync(kill = kill).outcomes["newbie"]
        )
        // Still forbidden: the id must NOT be queued for re-drive.
        h.etags.set(null)
        h.sync(kill = kill)
        assertFalse(
            "a still-revoked record must not be marked unresolved",
            h.etags.unresolvedIds.contains("newbie")
        )
    }


    /**
     * A manifest gated by `minAppVersion` must leave an INCOMPATIBLE marker, so the
     * source is VISIBLE with a reason and the upgrade reconciler has something to
     * revive. The early "Skipped(requires app X)" short-circuit in syncEntry
     * prevented both (2026-10-20, found while planning the upgrade-reconcile drill).
     */
    @Test
    fun minAppVersionGatedCandidateLeavesIncompatibleMarker() = runTest {
        val manifestUrl = "https://cdn.example/plugins/futurex/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("futurex", 1, manifestUrl)),
            manifestUrl to manifestBytes("futurex", 1) { it.put("minAppVersion", "99.0.0") }
        )
        val h = harness(bodies)
        val result = h.sync()
        assertTrue(
            "gated candidate must be rejected, got ${result.outcomes["futurex"]}",
            result.outcomes["futurex"] is PluginSyncEngine.EntryOutcome.Rejected
        )
        val rec = h.index.get("futurex")!!
        assertEquals(PluginStatus.INCOMPATIBLE, rec.status)
        assertEquals(null, rec.activeVersion)
        assertTrue("the marker must carry the manifest so it can be re-verified", !rec.manifestJson.isNullOrEmpty())
        assertFalse(h.registry.isKnown("futurex"))
    }

    /**
     * The other half: once the app is new enough, the upgrade reconciler revives
     * the marker to AVAILABLE (the app upgrade is the recovery path).
     */
    @Test
    fun upgradeReconcilerRevivesMinAppVersionMarker() = runTest {
        val manifestUrl = "https://cdn.example/plugins/futurex/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("futurex", 1, manifestUrl)),
            manifestUrl to manifestBytes("futurex", 1) { it.put("minAppVersion", "99.0.0") }
        )
        val h = harness(bodies)
        h.sync()
        assertEquals(PluginStatus.INCOMPATIBLE, h.index.get("futurex")!!.status)

        // A newer app still cannot satisfy minAppVersion 99.0.0.
        val trustKeys = io.mockk.mockk<
            com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
        > {
            every { current() } returns h.trust
        }
        val reconciler = com.exapps.mangaworld.core.source.plugins.PluginUpgradeReconciler(
            h.index,
            PluginStore(h.index, kotlinx.coroutines.Dispatchers.Unconfined),
            trustKeys,
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        // An app that is still too old must NOT revive the marker.
        assertEquals(
            "an app that is still too old must NOT revive the marker",
            0, reconciler.reconcile("1.0.0")
        )
        assertEquals(PluginStatus.INCOMPATIBLE, h.index.get("futurex")!!.status)
        // A new enough app revives it — this is the upgrade-reconcile recovery.
        assertEquals("a new enough app must revive the marker", 1, reconciler.reconcile("99.0.0"))
        assertEquals(PluginStatus.AVAILABLE, h.index.get("futurex")!!.status)
    }


    /**
     * Gate 0 query 4 (`trust_reject x reason`) had NO emitter before v9.5.0 — the
     * only trace of a rejected distribution payload was a Crashlytics breadcrumb,
     * which is not aggregatable. This pins the signal at the download path.
     */
    @Test
    fun badSignatureIsReportedAsATrustRejection() = runTest {
        val manifestUrl = "https://cdn.example/plugins/badsig/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to indexJson(Triple("badsig", 1, manifestUrl)),
            // Tamper after signing: the signature no longer covers these bytes.
            manifestUrl to manifestBytes("badsig", 1) { it.put("baseUrl", "https://evil.example") }
        )
        val h = harness(bodies)
        h.sync()
        assertTrue(
            "expected a trust rejection for the tampered payload, got ${h.telemetry.trustRejects}",
            h.telemetry.trustRejects.any {
                it.id == "badsig" && it.reason == "BAD_SIGNATURE"
            }
        )
    }

}
