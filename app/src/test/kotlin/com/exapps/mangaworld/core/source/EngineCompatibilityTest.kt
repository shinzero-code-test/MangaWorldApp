package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.CompatibilityResult
import com.exapps.mangaworld.core.source.plugins.EngineCompatibility
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: engine/bridge/app compatibility matrix. Unknown versions fail closed
 * here (not as schema errors), so old apps decline newer manifests cleanly.
 */
class EngineCompatibilityTest {

    private val host = PluginTestFixtures.HOST

    private fun check(
        engine: SourceEngine? = SourceEngine.MADARA,
        engineApi: Int? = 2,
        bridgeApi: Int? = null,
        minAppVersion: String? = "8.8.0"
    ) = EngineCompatibility.check(engine, engineApi, bridgeApi, minAppVersion, host)

    @Test
    fun supportedCombinationPasses() {
        assertTrue(check() is CompatibilityResult.Compatible)
        assertTrue(
            check(engine = SourceEngine.SCRIPT, engineApi = 1, bridgeApi = 1) is CompatibilityResult.Compatible
        )
    }

    @Test
    fun unknownEngineFailsClosed() {
        val result = check(engine = null)
        assertTrue(result is CompatibilityResult.Incompatible)
    }

    @Test
    fun unsupportedEngineApiFails() {
        assertTrue(check(engineApi = 9) is CompatibilityResult.Incompatible)
        assertTrue(check(engineApi = null) is CompatibilityResult.Incompatible)
    }

    @Test
    fun bridgeApiEnforcedForScriptsOnly() {
        assertTrue(
            check(engine = SourceEngine.SCRIPT, engineApi = 1, bridgeApi = 9) is CompatibilityResult.Incompatible
        )
        assertTrue(
            check(engine = SourceEngine.SCRIPT, engineApi = 1, bridgeApi = null) is CompatibilityResult.Incompatible
        )
        // Theme engines ignore bridgeApi entirely.
        assertTrue(check(engine = SourceEngine.API, engineApi = 1, bridgeApi = 99) is CompatibilityResult.Compatible)
    }

    @Test
    fun minAppVersionGate() {
        assertTrue(check(minAppVersion = "9.0.0") is CompatibilityResult.Incompatible)
        assertTrue(check(minAppVersion = "8.8.0") is CompatibilityResult.Compatible)
        assertTrue(check(minAppVersion = "8.7.5") is CompatibilityResult.Compatible)
        assertTrue(check(minAppVersion = null) is CompatibilityResult.Incompatible)
    }

    @Test
    fun versionCompare() {
        val cmp = EngineCompatibility::compareVersions
        assertEquals(0, cmp("8.8.0", "8.8.0"))
        assertTrue(cmp("8.8.0", "9.0.0") < 0)
        assertTrue(cmp("9.0.0", "8.8.0") > 0)
        assertTrue(cmp("8.8", "8.8.0") == 0)
        assertTrue(cmp("8.10.0", "8.9.0") > 0)
    }
}
