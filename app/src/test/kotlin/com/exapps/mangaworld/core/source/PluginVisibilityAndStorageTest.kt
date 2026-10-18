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

    @Test
    fun scriptInstallRefusesToActivateWithoutItsScriptBytes() = runTest {
        val kp = PluginTestFixtures.generateKeyPair()
        val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val host = HostCapabilities(
            appVersion = "9.3.0",
            supportedEngines = SourceEngine.entries.associateWith { 1..99 },
            supportedBridgeApi = 9
        )
        val js = "function entry(){ return [] }".toByteArray()
        // script manifests must declare NO config keys (schema rule) — same shape
        // PluginScriptSyncTest signs.
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
        val manifest = com.exapps.mangaworld.core.source.plugins.ManifestParser(
            trustedKeys = trust, host = host
        ).parseAndVerify(manifestBytes).let {
            (it as com.exapps.mangaworld.core.source.plugins.ManifestResult.Valid).manifest
        }
        val index = FakeIndex()
        val store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)
        // Pre-seed the version dir with the manifest but NO source.js: the
        // post-condition must refuse rather than activate a half-payload.
        val dir = File(PluginStorage.versionDir(tmp.root.path, "runner", 1))
        dir.mkdirs()
        File(dir, "plugin.json").writeBytes(manifestBytes)
        val result = store.persistVerified(
            baseDir = tmp.root, id = "runner", manifest = manifest,
            manifestBytes = manifestBytes, origin = PluginOrigin.OFFICIAL, scriptBytes = js
        )
        assertTrue(
            "a script payload that did not land must not activate: $result",
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