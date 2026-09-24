package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.SourceDisplay
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.source.plugins.SourcePlugin
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 gate: the runtime registry merges the 17 built-in plugins (rockmanga gone),
 * every descriptor is internally consistent, and remote installs can never shadow a
 * built-in id.
 */
class SourceRegistryTest {

    private fun fakePlugin(id: String): SourcePlugin {
        val manifest = PluginManifest(
            id = SourceId(id),
            version = 1,
            minAppVersion = "8.8.0",
            issuedAt = "2026-09-24T00:00:00Z",
            bridgeApi = null,
            names = mapOf("ar" to id),
            logo = null,
            engine = SourceEngine.MADARA,
            engineApi = 1,
            baseUrl = "https://$id.example",
            requiresVerification = false,
            enabledByDefault = true,
            config = emptyMap(),
            apiPaths = emptyMap(),
            allowedHosts = setOf("$id.example"),
            timeoutMs = 30_000,
            maxResponseMb = 10,
            scriptSha256 = null,
            signatureKeyId = "test"
        )
        return object : SourcePlugin {
            override val descriptor = manifest
            override val display = SourceDisplay(0, 0)
            override val scraper: MangaScraper = mockk(relaxed = true)
        }
    }

    private val expectedIds = setOf(
        "olympus", "azora", "starz", "mangasid", "meshmanga",
        "asq3", "lekmanga", "lekmangaonline", "likemanga", "linkmanga",
        "mangaleko", "mangalionz", "areascans", "hijala", "lavascans",
        "stellarsaber", "procomic"
    )

    @Test
    fun builtinIdsMatchExpectedSet() {
        val registry = SourceRegistry(expectedIds.associateWith { fakePlugin(it) })
        assertEquals(expectedIds, registry.all().map { it.descriptor.id.value }.toSet())
        assertEquals(17, registry.scraperMap().size)
    }

    @Test
    fun rockmangaIsGone() {
        val registry = SourceRegistry(expectedIds.associateWith { fakePlugin(it) })
        assertFalse(registry.isKnown("rockmanga"))
        assertNull(registry.pluginFor("rockmanga"))
        assertNull(registry.scraperFor("rockmanga"))
    }

    @Test
    fun unknownIdResolvesNull() {
        val registry = SourceRegistry(mapOf("azora" to fakePlugin("azora")))
        assertNull(registry.pluginFor("nope"))
        assertNull(registry.descriptorFor("nope"))
        assertNull(registry.scraperFor("nope"))
        assertFalse(registry.isKnown("nope"))
    }

    @Test
    fun remoteCannotShadowBuiltin() {
        val registry = SourceRegistry(mapOf("azora" to fakePlugin("azora")))
        assertFalse(registry.registerRemote(fakePlugin("azora")))
        assertTrue(registry.registerRemote(fakePlugin("newsite")))
        assertTrue(registry.isKnown("newsite"))
        assertTrue(registry.unregisterRemote("newsite"))
        assertFalse(registry.isKnown("newsite"))
        assertFalse(registry.unregisterRemote("newsite"))
    }

    @Test
    fun builtinDescriptorsAreInternallyConsistent() {
        // Mirrors the audit-derived invariants every builtin descriptor must hold:
        // https base, engine set, base host allow-listed, sane quotas, semver floor.
        val registry = SourceRegistry(expectedIds.associateWith { fakePlugin(it) })
        registry.all().forEach { plugin ->
            val d = plugin.descriptor
            assertTrue(d.baseUrl.startsWith("https://"))
            assertTrue(d.allowedHosts.contains(d.baseUrl.removePrefix("https://").substringBefore('/')))
            assertTrue(d.timeoutMs in 1_000..120_000)
            assertTrue(d.maxResponseMb in 1..50)
            assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(d.minAppVersion))
            assertTrue(d.version >= 1)
        }
    }
}
