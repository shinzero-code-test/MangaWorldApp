package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.CappedBytes
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.SourceDisplayResolver
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import com.exapps.mangaworld.core.source.plugins.PluginStorage
import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.rowStateFor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * v9.3.0 — Train 2 regression tests: row visibility for the marker statuses
 * (audit O-16/B12), the bounded body reader (audit B8), and the activation
 * post-condition (audit B2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginVisibilityAndStorageTest {

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

    private fun previewJson(id: String) =
        """{"id":"$id","version":1,"engine":"script","baseUrl":"https://$id.example",""" +
            """"names":{"ar":"$id","en":"$id"},"allowedHosts":["$id.example"]}"""

    private fun mapper(index: PluginIndexStore): SourceUiMapper {
        val context: android.content.Context = mockk { every { getString(any<Int>()) } returns "?" }
        return SourceUiMapper(
            registry = SourceUiTestFixtures.registry("hijala"),
            resolver = SourceDisplayResolver(context),
            indexStore = index
        )
    }

    // ─── O-16/B12: marker records must be visible ───────────────────────────

    @Test
    fun markerStatusesMapToTheirOwnPostures() {
        // Each of these used to be SERVING (or produced no row at all).
        assertEquals(PluginRowState.INCOMPATIBLE, rowStateFor(record(PluginStatus.INCOMPATIBLE)))
        assertEquals(PluginRowState.AVAILABLE, rowStateFor(record(PluginStatus.AVAILABLE)))
        assertEquals(PluginRowState.REVOKED, rowStateFor(record(PluginStatus.REVOKED)))
        // Quarantine still wins over "unregistered" (its action is re-smoke).
        assertEquals(
            PluginRowState.QUARANTINED,
            rowStateFor(record(PluginStatus.QUARANTINED), unregistered = true)
        )
    }

    @Test
    fun incompatibleMarkerRecordRendersWithoutAnActiveVersion() = runTest {
        // The upgrade reconciler's marker has activeVersion = null — the mapper
        // previously required one, so the row simply did not exist.
        val index = FakeIndex()
        index.map["future"] = PluginIndexRecord(
            id = "future", activeVersion = null, previousVersion = null,
            origin = PluginOrigin.OFFICIAL, status = PluginStatus.INCOMPATIBLE,
            manifestJson = previewJson("future")
        )
        val m = mapper(index)
        m.refresh()
        val row = m.entries().single { it.id == "future" }
        assertEquals(PluginRowState.INCOMPATIBLE, row.rowState)
        assertEquals("future", m.heldDetails()["future"]!!.id)
    }

    @Test
    fun availableAndRevokedMarkersRenderWithFacts() = runTest {
        val index = FakeIndex()
        for (id in listOf("revived", "killed")) {
            index.map[id] = PluginIndexRecord(
                id = id, activeVersion = 1, previousVersion = null,
                origin = PluginOrigin.OFFICIAL,
                status = if (id == "revived") PluginStatus.AVAILABLE else PluginStatus.REVOKED,
                manifestJson = previewJson(id)
            )
        }
        val m = mapper(index)
        m.refresh()
        assertEquals(PluginRowState.AVAILABLE, m.entry("revived")?.rowState)
        assertEquals(PluginRowState.REVOKED, m.entry("killed")?.rowState)
        // Both expose their consent facts so the sheet can explain + re-sync.
        assertEquals("revived", m.heldDetails()["revived"]!!.id)
        assertEquals("killed", m.heldDetails()["killed"]!!.id)
    }

    // ─── B8: bounded reads ──────────────────────────────────────────────────

    @Test
    fun cappedReadStopsAtTheCapWithoutBufferingEverything() {
        // A chunked response has no Content-Length, so the pre-check cannot help;
        // the reader must bail the moment the cap is passed.
        val big = ByteArray(512 * 1024) { 'a'.code.toByte() }
        try {
            CappedBytes.read(big.inputStream(), maxBytes = 1024)
            fail("expected TooLarge")
        } catch (e: CappedBytes.TooLarge) {
            assertTrue("must trip near the cap, not after the whole body", e.read <= 1024 + 8192)
        }
    }

    @Test
    fun cappedReadReturnsSmallBodiesIntact() {
        val body = ByteArray(2048) { (it % 251).toByte() }
        val read = CappedBytes.read(body.inputStream(), maxBytes = 8192)
        assertTrue(body.contentEquals(read))
    }

    // ─── B2: activation requires the bytes to have landed ───────────────────

    /** Signed SCRIPT manifest + its script bytes, shaped like PluginScriptSyncTest. */
    private data class ScriptCase(
        val manifest: com.exapps.mangaworld.core.source.plugins.PluginManifest,
        val manifestBytes: ByteArray,
        val scriptBytes: ByteArray,
        val trust: Map<String, ByteArray>
    )

    private fun scriptCase(): ScriptCase {
        val kp = PluginTestFixtures.generateKeyPair()
        val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val js = "function entry(){ return [] }".toByteArray()
        // script manifests declare NO config keys (schema rule).
        val node = PluginTestFixtures.manifestTree {
            it.put("id", "runner")
            it.put("version", 1)
            it.put("engine", "script")
            it.put("engineApi", 1)
            it.put("bridgeApi", 1)
            it.put("scriptSha256", sha256Hex(js))
            it.put("baseUrl", "https://runner.example")
            it.putArray("allowedHosts").add("runner.example")
            it.remove("config")
        }
        val manifestBytes = PluginTestFixtures.signManifest(node, "k1", kp.private)
        val host = HostCapabilities(
            appVersion = "9.3.0",
            supportedEngines = SourceEngine.entries.associateWith { 1..99 },
            supportedBridgeApi = 9
        )
        val parsed = com.exapps.mangaworld.core.source.plugins.ManifestParser(
            trustedKeys = trust, host = host
        ).parseAndVerify(manifestBytes)
        val manifest = (parsed as com.exapps.mangaworld.core.source.plugins.ManifestResult.Valid).manifest
        return ScriptCase(manifest, manifestBytes, js, trust)
    }

    @Test
    fun scriptInstallRepairsAMissingScriptPayload() = runTest {
        // v9.2.5/B2: a version dir that exists but lost source.js is REPAIRED
        // from the staged, hash-verified copy — immutability only refuses bytes
        // that are present and differ.
        val c = scriptCase()
        val index = FakeIndex()
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val dir = File(PluginStorage.versionDir(tmp.root.path, "runner", 1))
        dir.mkdirs()
        File(dir, "plugin.json").writeBytes(c.manifestBytes)
        assertTrue(!File(dir, "source.js").exists())

        val result = store.persistVerified(
            baseDir = tmp.root, id = "runner", manifest = c.manifest,
            manifestBytes = c.manifestBytes, origin = PluginOrigin.OFFICIAL,
            scriptBytes = c.scriptBytes
        )
        assertTrue("expected the torn dir to be repaired, got $result",
            result is PluginStore.InstallResult.Installed)
        assertEquals(1, index.get("runner")!!.activeVersion)
        assertTrue("script bytes must land", File(dir, "source.js").isFile)
        assertTrue(c.scriptBytes.contentEquals(File(dir, "source.js").readBytes()))
    }

    @Test
    fun activationRefusesWhenTheBytesCannotLand() = runTest {
        // v9.3.0/B2 post-condition: the pointer moves only after the bytes it
        // names are read back and byte-compared. Block the version path with a
        // FILE so mkdirs fails — the read-back then sees no payload and the
        // install is refused instead of activating a dangling pointer.
        val c = scriptCase()
        val index = FakeIndex()
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        val versionPath = File(PluginStorage.versionDir(tmp.root.path, "runner", 1))
        versionPath.parentFile.mkdirs()
        versionPath.writeText("not a directory")

        val result = store.persistVerified(
            baseDir = tmp.root, id = "runner", manifest = c.manifest,
            manifestBytes = c.manifestBytes, origin = PluginOrigin.OFFICIAL,
            scriptBytes = c.scriptBytes
        )
        assertTrue(
            "an install whose bytes never landed must be refused: $result",
            result is PluginStore.InstallResult.Rejected
        )
        assertTrue("no pointer may be written", index.get("runner") == null)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun record(status: PluginStatus) = PluginIndexRecord(
        id = "x", activeVersion = 1, previousVersion = null,
        origin = PluginOrigin.OFFICIAL, status = status,
        manifestJson = previewJson("x")
    )
}