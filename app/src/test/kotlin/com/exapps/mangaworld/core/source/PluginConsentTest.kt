package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.SourceDisplayResolver
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import com.exapps.mangaworld.core.source.plugins.displayName
import com.exapps.mangaworld.core.source.plugins.parseManifestPreview
import com.exapps.mangaworld.core.source.plugins.rowStateFor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.1.0 consent surfaces: lenient preview parsing, row-posture mapping, and
 * index-held extras in the real mapper. Display-only layers: a lying preview
 * mis-renders at worst — approval always re-verifies authoritatively.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginConsentTest {

    private val previewJson = """
        {"id":"gated","version":3,"engine":"mangareader","baseUrl":"https://gated.example",
         "names":{"ar":"مقيد","en":"Gated"},"allowedHosts":["gated.example","cdn.gated.example"],
         "requiresPermission":true,"signature":"ed25519:k1:abc"}
    """.trimIndent()

    @Test
    fun previewParsesDisplayFacts() {
        val preview = parseManifestPreview(previewJson)!!
        assertEquals("gated", preview.id)
        assertEquals(3, preview.version)
        assertEquals("mangareader", preview.engineName)
        assertEquals("https://gated.example", preview.baseUrl)
        assertEquals(listOf("gated.example", "cdn.gated.example"), preview.hosts)
        assertEquals(true, preview.requiresPermission)
        assertEquals("مقيد", preview.displayName("ar"))
        assertEquals("Gated", preview.displayName("en"))
    }

    @Test
    fun previewToleratesMinimalJson() {
        val preview = parseManifestPreview("""{"id":"x"}""")!!
        assertEquals("x", preview.id)
        assertEquals(0, preview.version)
        assertEquals("", preview.engineName)
        assertTrue(preview.hosts.isEmpty())
        assertEquals("x", preview.displayName("ar"))
    }

    @Test
    fun previewRejectsGarbage() {
        assertNull(parseManifestPreview(null))
        assertNull(parseManifestPreview(""))
        assertNull(parseManifestPreview("not json"))
        assertNull(parseManifestPreview("[1,2]"))
        assertNull(parseManifestPreview("""{"version":1}"""))
    }

    @Test
    fun rowStateMatrix() {
        // No record (pure builtin): normal serving.
        assertEquals(PluginRowState.SERVING, rowStateFor(null))
        // Enabled/installed serving records: normal.
        assertEquals(
            PluginRowState.SERVING,
            rowStateFor(record(PluginStatus.ENABLED))
        )
        // Disabled WITH bytes: approvable hold (holds, deferrals, opt-outs).
        assertEquals(PluginRowState.HELD, rowStateFor(record(PluginStatus.DISABLED)))
        // Disabled WITHOUT bytes (restore ref, never installed): not approvable.
        assertEquals(
            PluginRowState.SERVING,
            rowStateFor(record(PluginStatus.DISABLED).copy(activeVersion = null, manifestJson = null))
        )
        // Quarantine: re-smoke surface.
        assertEquals(
            PluginRowState.QUARANTINED,
            rowStateFor(record(PluginStatus.QUARANTINED))
        )
        // Terminal/incompatible states render as normal rows (builtin serving).
        assertEquals(PluginRowState.SERVING, rowStateFor(record(PluginStatus.REVOKED)))
        assertEquals(PluginRowState.SERVING, rowStateFor(record(PluginStatus.INCOMPATIBLE)))
        assertEquals(PluginRowState.SERVING, rowStateFor(record(PluginStatus.AVAILABLE)))
    }

    @Test
    fun mapperListsHeldExtrasWithPreview() = runTest {
        val index = object : com.exapps.mangaworld.core.source.plugins.PluginIndexStore {
            val rec = PluginIndexRecord(
                id = "gated", activeVersion = 1, previousVersion = null,
                origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
                manifestJson = previewJson
            )
            override suspend fun get(id: String) = rec.takeIf { it.id == id }
            override suspend fun getAll() = listOf(rec)
            override suspend fun put(record: PluginIndexRecord) = Unit
            override suspend fun remove(id: String) = false
        }
        val context: android.content.Context = mockk {
            every { getString(any<Int>()) } returns "?"
        }
        val mapper = SourceUiMapper(
            registry = SourceUiTestFixtures.registry("hijala"),
            resolver = SourceDisplayResolver(context),
            indexStore = index
        )
        // Before refresh: extras invisible (snapshot empty by construction).
        assertTrue(mapper.entries().none { it.id == "gated" })
        mapper.refresh()
        val extra = mapper.entries().single { it.id == "gated" }
        // Name follows the device locale via the same precedence the resolver
        // uses (asserted per-locale in previewParsesDisplayFacts); here we pin
        // it to whatever the JVM default resolves to, deterministically.
        val preview = parseManifestPreview(previewJson)!!
        assertEquals(
            preview.displayName(java.util.Locale.getDefault().language),
            extra.name
        )
        assertEquals(0, extra.logoRes)
        assertEquals(PluginRowState.HELD, extra.rowState)
        assertEquals(extra, mapper.entry("gated"))
        // Consent facts resolve for the sheet.
        assertEquals("gated", mapper.heldDetails()["gated"]!!.id)
        // Registered rows keep serving posture when no record exists for them.
        assertEquals(PluginRowState.SERVING, mapper.entry("hijala")!!.rowState)
    }

    private fun record(status: PluginStatus) = PluginIndexRecord(
        id = "gated",
        activeVersion = 1,
        previousVersion = null,
        origin = PluginOrigin.OFFICIAL,
        status = status,
        manifestJson = previewJson
    )
}
