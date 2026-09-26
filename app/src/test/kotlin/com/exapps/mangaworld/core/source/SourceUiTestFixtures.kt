package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.SourceDisplay
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.source.plugins.SourcePlugin
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.SourceUiEntry
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/**
 * ViewModel-test doubles for the Phase 2 registry world. VMs under test resolve
 * display data through [SourceUiMapper] (and some through [SourceRegistry]);
 * these fakes serve a fixed id set with no Android and no Hilt.
 */
object SourceUiTestFixtures {

    val DEFAULT_IDS = arrayOf("azora", "olympus", "starz", "hijala", "lekmanga")

    /** Mirrors production descriptor policy for the ids tests exercise. */
    private val VERIFY_GATED = setOf(
        "olympus", "starz", "asq3", "hijala", "lavascans", "stellarsaber", "procomic"
    )

    fun entry(id: String) = SourceUiEntry(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        logoRes = 0,
        engine = SourceEngine.MADARA,
        requiresVerification = id in VERIFY_GATED,
        hostHint = "$id.example"
    )

    fun mapper(vararg ids: String = DEFAULT_IDS): SourceUiMapper {
        val m = mockk<SourceUiMapper>()
        val entries = ids.map { entry(it) }
        every { m.entries() } returns entries
        every { m.entries(any()) } returns entries
        every { m.entry(any()) } answers { entry(firstArg()) }
        every { m.entry(any(), any()) } answers { entry(firstArg()) }
        every { m.displayName(any()) } answers { firstArg<String>() }
        every { m.displayName(any(), any()) } answers { firstArg<String>() }
        coEvery { m.refresh() } returns Unit
        every { m.heldDetails() } returns emptyMap()
        return m
    }

    private fun fakePlugin(id: String, scraper: MangaScraper? = null): SourcePlugin {
        val manifest = PluginManifest(
            id = SourceId(id),
            version = 1,
            minAppVersion = "8.9.0",
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
        val sc = scraper ?: mockk(relaxed = true)
        return object : SourcePlugin {
            override val descriptor = manifest
            override val display = SourceDisplay(0, 0)
            override val scraper: MangaScraper = sc
        }
    }

    /** Real registry over fake plugins (scraper lookups, descriptor reads, guards). */
    fun registry(
        vararg ids: String = DEFAULT_IDS,
        scrapers: Map<String, MangaScraper> = emptyMap()
    ): SourceRegistry = SourceRegistry(
        ids.associateWith { fakePlugin(it, scrapers[it]) }
    )
}
