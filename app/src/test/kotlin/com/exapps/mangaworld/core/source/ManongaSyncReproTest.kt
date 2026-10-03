package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginRunnerFactory
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.script.ScriptContextFactory
import com.exapps.mangaworld.core.source.script.ScriptLogger
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import com.exapps.mangaworld.core.source.sync.EtagStore
import com.exapps.mangaworld.core.source.sync.OkHttpPluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginFetcher
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.core.source.sync.PostSmoke
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Device repro (v9.1.4 fleet diagnosis): the promoted `manonga` payload fails
 * the REAL sync install path on-device (`sync manonga failed: null`) while the
 * [ScriptCanaryTest] fixture-execution gate stays green. This test drives the
 * exact production pieces the canary skips — real [PluginStore.verify],
 * [ScriptPluginLoader.load] via [PluginRunnerFactory.createScript], real
 * persist + [registerVerified] — with the byte-identical promoted payload, so
 * a JVM failure prints the full (unminified) stack CI-side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManongaSyncReproTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dashboardFile(vararg parts: String): File {
        val anchored = System.getProperty("canary.dashboard.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, parts.joinToString("/")) }
        if (anchored != null) {
            if (anchored.isFile) return anchored
            error("canary file missing: ${anchored.path}")
        }
        val start = runCatching { File(System.getProperty("user.dir")).canonicalFile }
            .getOrElse { File(".").absoluteFile }
        val chain = generateSequence(start) { it.parentFile }.take(6).toList()
        val rel = "dashboard/" + parts.joinToString("/")
        return (chain.map { File(it, rel) } + File(rel)).firstOrNull { it.isFile }
            ?: error("canary file missing: $rel (start=$start)")
    }

    private class FakeIndex : PluginIndexStore {
        val map = mutableMapOf<String, PluginIndexRecord>()
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private class FakeFetcher(val bodies: Map<String, ByteArray>) : PluginFetcher {
        val requests = mutableListOf<String>()
        override suspend fun get(
            url: String,
            allowedHosts: Set<String>,
            maxBytes: Long,
            headers: Map<String, String>
        ): PluginFetcher.FetchResult {
            requests += url
            return PluginFetcher.FetchResult(
                bodies[url] ?: throw PluginFetcher.FetchFailure.Http(404, url),
                url, null
            )
        }
    }

    /** Promoted version follows `public/plugins/index.json` (see ScriptCanaryTest). */
    private fun promotedVersion(): Int {
        val text = dashboardFile("public", "plugins", "index.json").readText()
        val entries = org.json.JSONObject(text).getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.getString("id") == "manonga") return e.getInt("version")
        }
        error("manonga entry missing from promoted index")
    }

    @Test
    fun promotedManongaInstallsThroughRealSyncPath() = runTest {
        val v = promotedVersion()
        val manifestBytes = dashboardFile("public", "plugins", "manonga", "v$v", "plugin.json").readBytes()
        val scriptBytes = dashboardFile("public", "plugins", "manonga", "v$v", "source.js").readBytes()
        val manifestUrl = "https://cdn.example/plugins/manonga/v1/plugin.json"
        val indexUrl = "https://cdn.example/plugins/index.json"
        val bodies = mapOf(
            indexUrl to
                """{"schemaVersion":1,"updatedAt":"2026-09-27T13:59:34Z","entries":[{"id":"manonga","version":1,"kind":"script","minAppVersion":"9.1.0","manifestUrl":"$manifestUrl"}]}"""
                    .toByteArray(Charsets.UTF_8),
            manifestUrl to manifestBytes,
            "https://cdn.example/plugins/manonga/v1/source.js" to scriptBytes
        )
        val index = FakeIndex()
        val store = PluginStore(index, Dispatchers.Unconfined)
        val registry = SourceUiTestFixtures.registry()
        val scriptRunnerFactory = ScriptRunnerFactory(
            ScriptContextFactory(),
            ScriptTestSupport.FakeFetcher(mutableMapOf()),
            ScriptLogger { _, _, _ -> },
            RecordingPluginTelemetry(),
            Dispatchers.Unconfined
        )
        val runnerFactory = PluginRunnerFactory(
            OkHttpClient(),
            mockk<SettingsRepository>(relaxed = true),
            ScriptPluginLoader(scriptRunnerFactory)
        )
        val engine = PluginSyncEngine(
            index, store, registry, FakeFetcher(bodies), object : EtagStore {
                override fun get(): String? = null
                override fun set(etag: String?) = Unit
            },
            runnerFactory, mockk(relaxed = true), RecordingPluginTelemetry(), Dispatchers.Unconfined
        )
        engine.log = { println("ENGINE-LOG: $it") }
        val result = engine.sync(
            trustedKeys = PluginTrust.pinnedKeys(),
            host = PluginTrust.productionCapabilities("9.1.4"),
            indexUrl = indexUrl,
            postSmoke = PostSmoke.Custom({ true }),
            baseDir = tmp.root
        )
        println("OUTCOMES: ${result.outcomes}")
        val outcome = result.outcomes["manonga"]
        assertTrue(
            "promoted manonga must install, got: $outcome",
            outcome is PluginSyncEngine.EntryOutcome.Updated
        )
    }
}
