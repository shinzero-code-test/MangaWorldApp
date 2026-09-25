package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.DescriptorScraper
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginRunnerFactory
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import com.exapps.mangaworld.core.source.sync.EtagStore
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.core.source.sync.PostSmoke
import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * Phase 3 sync pairing: script payloads download beside their manifest, pin to
 * `scriptSha256`, and serve through the sandbox; descriptor engines pair
 * generic runners; engine changes and runner-less engines hold/defer.
 *
 * Mirrors the `PluginSyncEngineTest` harness (fakes, signing) with a REAL
 * runner factory — the pairing itself is what's under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginScriptSyncTest {

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

    private class FakeFetcher(val bodies: MutableMap<String, ByteArray>) : PluginFetcher {
        override suspend fun get(
            url: String,
            allowedHosts: Set<String>,
            maxBytes: Long,
            headers: Map<String, String>
        ): PluginFetcher.FetchResult {
            val body = bodies[url] ?: throw PluginFetcher.FetchFailure.Http(404, url)
            if (body.size > maxBytes) throw PluginFetcher.FetchFailure.TooLarge(body.size.toLong())
            return PluginFetcher.FetchResult(body, url, null)
        }
    }

    private val kp = PluginTestFixtures.generateKeyPair()
    private val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))
    private val host = HostCapabilities(
        appVersion = "8.10.0",
        supportedEngines = SourceEngine.entries.associateWith { 1..99 },
        supportedBridgeApi = 9
    )
    private val settings: SettingsRepository = mockk(relaxed = true)

    private fun manifestBytes(id: String, version: Int, mutate: (ObjectNode) -> Unit = {}): ByteArray {
        val node = PluginTestFixtures.manifestTree {
            it.put("id", id)
            it.put("version", version)
            it.put("engineApi", 1)
            it.put("issuedAt", Instant.now().toString())
            mutate(it)
        }
        return PluginTestFixtures.signManifest(node, "k1", kp.private)
    }

    private fun indexJson(vararg entries: Triple<String, Int, String>): ByteArray {
        val body = entries.joinToString(",") { (id, version, url) ->
            """{"id":"$id","version":$version,"kind":"descriptor","minAppVersion":"8.9.0","manifestUrl":"$url"}"""
        }
        return """{"schemaVersion":1,"updatedAt":"${Instant.now()}","entries":[$body]}"""
            .toByteArray(Charsets.UTF_8)
    }

    private val scriptSource =
        "function home(ctx){ return {featured: [], latest: [], trending: []}; }" +
            "function detail(ctx){ return {id:'a', slug:'a', title:'A'}; }" +
            "function pages(ctx){ return []; }" +
            "function search(ctx){ return []; }" +
            "function browse(ctx){ return []; }"
    private val scriptBytes = scriptSource.toByteArray(Charsets.UTF_8)

    private fun engine(
        bodies: MutableMap<String, ByteArray>,
        index: FakeIndex = FakeIndex()
    ): Triple<PluginSyncEngine, FakeIndex, SettingsRepository> {
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val runners = PluginRunnerFactory(
            client = OkHttpClient(),
            settingsRepo = settings,
            scriptLoader = ScriptPluginLoader(
                ScriptRunnerFactory(
                    ScriptTestSupport.sandbox,
                    ScriptTestSupport.FakeFetcher(),
                    ScriptTestSupport.logger,
                    kotlinx.coroutines.Dispatchers.Unconfined
                )
            )
        )
        val e = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            runners, settings, kotlinx.coroutines.Dispatchers.Unconfined
        )
        e.log = { }
        return Triple(e, index, settings)
    }

    private suspend fun Triple<PluginSyncEngine, FakeIndex, SettingsRepository>.sync(): PluginSyncEngine.SyncResult =
        first.sync(
            trustedKeys = trust,
            host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }),
            baseDir = tmp.root
        )

    @Test
    fun scriptNewSourceRegistersAndServes() = runTest {
        val manifestUrl = "https://cdn.example/plugins/scriptnew/v1/plugin.json"
        val scriptUrl = "https://cdn.example/plugins/scriptnew/v1/source.js"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("scriptnew", 1, manifestUrl)),
            manifestUrl to manifestBytes("scriptnew", 1) {
                it.put("engine", "script")
                it.put("bridgeApi", 1)
                it.put("scriptSha256", ScriptPluginLoader.sha256Hex(scriptBytes))
                it.put("baseUrl", "https://scriptnew.example")
                it.remove("config")
            },
            scriptUrl to scriptBytes
        )
        val index = FakeIndex()
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val runners = PluginRunnerFactory(
            OkHttpClient(), settings,
            ScriptPluginLoader(
                ScriptRunnerFactory(
                    ScriptTestSupport.sandbox, ScriptTestSupport.FakeFetcher(),
                    ScriptTestSupport.logger, kotlinx.coroutines.Dispatchers.Unconfined
                )
            )
        )
        val e = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            runners, settings, kotlinx.coroutines.Dispatchers.Unconfined
        )
        e.log = { }
        val result = e.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(result.outcomes["scriptnew"] is PluginSyncEngine.EntryOutcome.Updated)
        val scraper = registry.scraperFor("scriptnew")
        assertTrue(
            "expected ScriptScraper, got ${scraper?.javaClass?.simpleName}",
            scraper is com.exapps.mangaworld.core.source.script.ScriptScraper
        )
        assertEquals("https://scriptnew.example", registry.descriptorFor("scriptnew")!!.baseUrl)
        assertEquals(PluginStatus.ENABLED, index.get("scriptnew")!!.status)
        assertTrue(java.io.File(tmp.root, "scriptnew/versions/1/source.js").isFile)
        coVerify { settings.toggleSource("scriptnew", true) }
    }

    @Test
    fun scriptHashMismatchRejected() = runTest {
        val manifestUrl = "https://cdn.example/plugins/scriptnew/v1/plugin.json"
        val scriptUrl = "https://cdn.example/plugins/scriptnew/v1/source.js"
        val tampered = scriptBytes.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("scriptnew", 1, manifestUrl)),
            manifestUrl to manifestBytes("scriptnew", 1) {
                it.put("engine", "script")
                it.put("bridgeApi", 1)
                it.put("scriptSha256", ScriptPluginLoader.sha256Hex(scriptBytes))
                it.put("baseUrl", "https://scriptnew.example")
                it.remove("config")
            },
            scriptUrl to tampered
        )
        val h = engine(bodies)
        val result = h.sync()
        val outcome = result.outcomes["scriptnew"]!!
        assertTrue(outcome is PluginSyncEngine.EntryOutcome.Rejected)
        assertTrue((outcome as PluginSyncEngine.EntryOutcome.Rejected).reason.contains("hash mismatch"))
        assertTrue(h.second.get("scriptnew") == null)
    }

    @Test
    fun descriptorNewSourceRegistersRunner() = runTest {
        val manifestUrl = "https://cdn.example/plugins/brandnew/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("brandnew", 1, manifestUrl)),
            manifestUrl to manifestBytes("brandnew", 1) {
                it.put("engine", "mangareader")
                it.put("baseUrl", "https://brandnew.example")
            }
        )
        val index = FakeIndex()
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val runners = PluginRunnerFactory(
            OkHttpClient(), settings,
            ScriptPluginLoader(
                ScriptRunnerFactory(
                    ScriptTestSupport.sandbox, ScriptTestSupport.FakeFetcher(),
                    ScriptTestSupport.logger, kotlinx.coroutines.Dispatchers.Unconfined
                )
            )
        )
        val e = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), FakeEtag(),
            runners, settings, kotlinx.coroutines.Dispatchers.Unconfined
        )
        e.log = { }
        val result = e.sync(
            trustedKeys = trust, host = host,
            indexUrl = "https://cdn.example/plugins/index.json",
            postSmoke = PostSmoke.Custom({ true }), baseDir = tmp.root
        )
        assertTrue(result.outcomes["brandnew"] is PluginSyncEngine.EntryOutcome.Updated)
        assertTrue(registry.scraperFor("brandnew") is DescriptorScraper)
        assertEquals("https://brandnew.example", registry.descriptorFor("brandnew")!!.baseUrl)
    }

    @Test
    fun optOutFreshInstallStaysDisabled() = runTest {
        val manifestUrl = "https://cdn.example/plugins/quieter/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("quieter", 1, manifestUrl)),
            manifestUrl to manifestBytes("quieter", 1) {
                it.put("engine", "madara")
                it.put("baseUrl", "https://quieter.example")
                it.put("enabledByDefault", false)
            }
        )
        val h = engine(bodies)
        val result = h.sync()
        assertTrue(result.outcomes["quieter"] is PluginSyncEngine.EntryOutcome.Updated)
        // Served (registered) but opted out: index DISABLED, settings untouched.
        assertEquals(PluginStatus.DISABLED, h.second.get("quieter")!!.status)
        coVerify(exactly = 0) { settings.toggleSource("quieter", any()) }
    }

    @Test
    fun astroNewSourceStillDeferred() = runTest {
        val manifestUrl = "https://cdn.example/plugins/astronew/v1/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("astronew", 1, manifestUrl)),
            manifestUrl to manifestBytes("astronew", 1) {
                it.put("engine", "astro")
                it.put("baseUrl", "https://astronew.example")
            }
        )
        val h = engine(bodies)
        val result = h.sync()
        // No generic ASTRO runner: retained DISABLED, never registered.
        assertEquals(PluginSyncEngine.EntryOutcome.NewSourceDeferred, result.outcomes["astronew"])
        assertEquals(PluginStatus.DISABLED, h.second.get("astronew")!!.status)
    }

    @Test
    fun engineChangeOnOverrideHeldForConsent() = runTest {
        val manifestUrl = "https://cdn.example/plugins/hijala/v2/plugin.json"
        val bodies = mutableMapOf(
            "https://cdn.example/plugins/index.json" to
                indexJson(Triple("hijala", 2, manifestUrl)),
            manifestUrl to manifestBytes("hijala", 2) {
                // Fixture hijala is MADARA; v2 claims MANGAREADER (as the real
                // hijala is) — an engine change on override.
                it.put("engine", "mangareader")
                it.put("baseUrl", "https://hijala.example")
            }
        )
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 1, null, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val h = engine(bodies, index)
        val result = h.sync()
        assertEquals(PluginSyncEngine.EntryOutcome.HeldForConsent, result.outcomes["hijala"])
        // v1 keeps serving; candidate retained DISABLED for review.
        assertEquals(1, h.second.get("hijala")!!.activeVersion)
        assertEquals(PluginStatus.DISABLED, h.second.get("hijala")!!.status)
    }
}
