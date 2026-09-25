package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.DescriptorScraper
import com.exapps.mangaworld.core.source.plugins.PluginRunnerFactory
import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 runner pairing: descriptor engines build, script builds with verified
 * bytes, runner-less engines defer (null — never an exception).
 */
class PluginRunnerFactoryTest {

    private val settings: SettingsRepository = mockk {
        every { getCookies(any()) } returns flowOf(null)
    }

    private fun factory(scriptBodies: Map<String, ByteArray> = emptyMap()) = PluginRunnerFactory(
        client = OkHttpClient(),
        settingsRepo = settings,
        scriptLoader = ScriptPluginLoader(
            ScriptRunnerFactory(
                ScriptTestSupport.sandbox,
                ScriptTestSupport.FakeFetcher(scriptBodies.toMutableMap()),
                ScriptTestSupport.logger,
                Dispatchers.Unconfined
            )
        )
    )

    private fun manifest(engine: SourceEngine) = ScriptTestSupport.manifest(
        id = "runner",
        engine = engine,
        bridgeApi = if (engine == SourceEngine.SCRIPT) 1 else null,
        scriptSha256 = if (engine == SourceEngine.SCRIPT) ScriptPluginLoader.sha256Hex(scriptBytes) else null
    )

    private val scriptBytes =
        "function home(ctx){ return {featured: [], latest: [], trending: []}; }".toByteArray()

    @Test
    fun madaraBuildsDescriptorRunner() {
        val scraper = factory().createDescriptor(manifest(SourceEngine.MADARA))
        assertTrue(scraper is DescriptorScraper)
        assertNotNull(scraper)
        assertEqualsRunnerId(scraper!!)
    }

    @Test
    fun mangaReaderBuildsDescriptorRunner() {
        val scraper = factory().createDescriptor(manifest(SourceEngine.MANGAREADER))
        assertTrue(scraper is DescriptorScraper)
    }

    @Test
    fun astroApiCustomHaveNoRunner() {
        val f = factory()
        assertNull(f.createDescriptor(manifest(SourceEngine.ASTRO)))
        assertNull(f.createDescriptor(manifest(SourceEngine.API)))
        assertNull(f.createDescriptor(manifest(SourceEngine.CUSTOM)))
        assertNull(f.createDescriptor(manifest(SourceEngine.SCRIPT)))
    }

    @Test
    fun scriptBuildsWithVerifiedBytes() {
        val m = manifest(SourceEngine.SCRIPT)
        val result = factory().createScript(m, scriptBytes)
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertEqualsRunnerId(result.getOrThrow())
    }

    @Test
    fun scriptRejectsTamperedBytes() {
        val m = manifest(SourceEngine.SCRIPT)
        val tampered = scriptBytes.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        val result = factory().createScript(m, tampered)
        assertNotNull(result)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun scriptNullBytesFailsNotDefers() {
        // Missing payload is a rejection (distinguishable from no-runner null).
        val result = factory().createScript(manifest(SourceEngine.SCRIPT), null)
        assertNotNull(result)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun scriptFactoryRefusesWrongEngine() {
        assertNull(factory().createScript(manifest(SourceEngine.MADARA), scriptBytes))
    }

    private fun assertEqualsRunnerId(scraper: com.exapps.mangaworld.core.data.remote.scraper.MangaScraper) {
        assertTrue(scraper.sourceId == "runner")
    }
}
