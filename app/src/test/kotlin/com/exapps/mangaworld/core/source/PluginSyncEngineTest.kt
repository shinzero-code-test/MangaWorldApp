package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private data class Harness(
        val engine: PluginSyncEngine,
        val index: FakeIndex,
        val fetcher: FakeFetcher,
        val registry: com.exapps.mangaworld.core.source.plugins.SourceRegistry
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
            baseDir = tmp.root
        )
    }

    private fun harness(
        bodies: MutableMap<String, ByteArray>,
        registryIds: Array<String> = arrayOf("hijala", "lavascans"),
        index: FakeIndex = FakeIndex(),
        etags: FakeEtag = FakeEtag(),
        fetcher: FakeFetcher = FakeFetcher(bodies)
    ): Harness {
        val registry = SourceUiTestFixtures.registry(*registryIds)
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(index, store, registry, fetcher, etags, kotlinx.coroutines.Dispatchers.Unconfined)
        return Harness(engine, index, fetcher, registry)
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
            index, store, registry, fetcher, FakeEtag(), kotlinx.coroutines.Dispatchers.Unconfined
        )
        val scraperBefore = registry.scraperFor("hijala")
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertEquals(PluginSyncEngine.EntryOutcome.Updated(hostsExpanded = false), result.outcomes["hijala"])
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
        val engine = PluginSyncEngine(
            index, store, registry, fetcher, FakeEtag(), kotlinx.coroutines.Dispatchers.Unconfined
        )
        suspend fun runSync() = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(runSync().outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        // Index rewinds to v1: refused, v2 keeps serving.
        bodies["https://cdn.example/plugins/index.json"] = indexJson(Triple("hijala", 1, v1Url))
        bodies[v1Url] = manifestBytes("hijala", 1)
        val second = runSync()
        assertEquals(PluginSyncEngine.EntryOutcome.DowngradeRefused, second.outcomes["hijala"])
        assertEquals("https://hijala-v2.example", registry.descriptorFor("hijala")!!.baseUrl)
        assertEquals(2, index.get("hijala")!!.activeVersion)
    }

    @Test
    fun postSmokeFailureRollsBackAndQuarantines() = runTest {
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
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        val result = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ false }), baseDir = tmp.root
        )
        assertEquals(PluginSyncEngine.EntryOutcome.RolledBack, result.outcomes["hijala"])
        // Builtin resumes; candidate quarantined with bytes retained.
        assertFalse(registry.isOverridden("hijala"))
        assertEquals(PluginStatus.QUARANTINED, index.get("hijala")!!.status)
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
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        suspend fun runSync(kill: String?) = engine.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            killSwitchJson = kill,
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(runSync(null).outcomes["hijala"] is PluginSyncEngine.EntryOutcome.Updated)
        assertTrue(registry.isOverridden("hijala"))
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
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
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
            index, store, registry, fetcher, etags, kotlinx.coroutines.Dispatchers.Unconfined
        )
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
        assertTrue(registry.registerRemote(custom))
        index.put(PluginIndexRecord("customx", 1, null, PluginOrigin.CUSTOM, PluginStatus.ENABLED, null))
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            kotlinx.coroutines.Dispatchers.Unconfined
        )
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
    }
}
