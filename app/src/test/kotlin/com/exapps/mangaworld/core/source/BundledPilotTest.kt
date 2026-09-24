package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2A pilot gate: the APK-bundled signed descriptors verify against the pinned
 * trust root and describe exactly the source their builtin serves. A packaging slip
 * (wrong engine/host, stale signature, drifted hosts) fails here — in CI, not on
 * a user's device at startup.
 */
class BundledPilotTest {

    private data class PilotExpectation(
        val id: String,
        val baseHost: String,
        val allowedHosts: Set<String>,
        val listPath: String,
        val arName: String
    )

    private val pilots = listOf(
        PilotExpectation(
            id = "hijala",
            baseHost = "hijala.com",
            allowedHosts = setOf("hijala.com", "blogger.googleusercontent.com"),
            listPath = "/manga/",
            arName = "حجالة مانجا"
        ),
        PilotExpectation(
            id = "lavascans",
            baseHost = "lavascans.com",
            allowedHosts = setOf("lavascans.com"),
            listPath = "/browse-manga/",
            arName = "لاڤا سكانز"
        )
    )

    private fun pilotBytes(id: String): ByteArray {
        // Unit-test workdir is the :app module dir; fall back to classpath if run elsewhere.
        val candidates = listOf(
            File("src/main/assets/plugins/$id/v1/plugin.json"),
            File(System.getProperty("user.dir"), "src/main/assets/plugins/$id/v1/plugin.json")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("bundled pilot asset missing for $id (tried ${candidates.map { it.path }})")
        return file.readBytes()
    }

    private val host: HostCapabilities = PluginTrust.productionCapabilities("8.9.0")

    @Test
    fun pilotsVerifyAgainstPinnedTrust() {
        for (pilot in pilots) {
            val parser = ManifestParser(
                trustedKeys = PluginTrust.pinnedKeys(),
                host = host
            )
            val result = parser.parseAndVerify(pilotBytes(pilot.id))
            assertTrue(
                "pilot ${pilot.id} must verify (got $result)",
                result is ManifestResult.Valid
            )
        }
    }

    @Test
    fun pilotDescriptorsMatchBuiltinFacts() {
        for (pilot in pilots) {
            val parser = ManifestParser(trustedKeys = PluginTrust.pinnedKeys(), host = host)
            val result = parser.parseAndVerify(pilotBytes(pilot.id))
            val manifest = (result as ManifestResult.Valid).manifest
            assertEquals(pilot.id, manifest.id.value)
            assertEquals(SourceEngine.MANGAREADER, manifest.engine)
            assertEquals(1, manifest.engineApi)
            assertEquals("https://${pilot.baseHost}", manifest.baseUrl)
            assertEquals(pilot.allowedHosts, manifest.allowedHosts)
            assertEquals(pilot.listPath, manifest.config["listPath"])
            assertEquals(pilot.arName, manifest.names["ar"])
            assertEquals(PluginTrust.OFFICIAL_KEY_ID, manifest.signatureKeyId)
            // Base host always implied in effective matching.
            assertTrue(manifest.effectiveHosts.contains(pilot.baseHost))
        }
    }
}
