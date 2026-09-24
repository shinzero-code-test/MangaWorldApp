package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 2A store gates: verified activation, pointer-only rollback, crash
 * invisibility, version immutability, and quota protection of active/previous.
 * Fake index + temp dir: no Android, no Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginStoreTest {

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

    // Permissive host: storage behavior under test, compat gating covered elsewhere.
    private val host = HostCapabilities(
        appVersion = "8.9.0",
        supportedEngines = SourceEngine.entries.associateWith { 1..99 },
        supportedBridgeApi = 9
    )

    private val kp = PluginTestFixtures.generateKeyPair()
    private val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))

    private fun manifestBytes(version: Int, timeoutMs: Int = 15000): ByteArray {
        val node = PluginTestFixtures.manifestTree {
            it.put("version", version)
            it.put("engineApi", 1)
            it.put("timeoutMs", timeoutMs)
        }
        return PluginTestFixtures.signManifest(node, "k1", kp.private)
    }

    private fun store(index: FakeIndex = FakeIndex()) =
        PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun installValidActivates() = runTest {
        val index = FakeIndex()
        val result = store(index).install(
            baseDir = tmp.root,
            manifestBytes = manifestBytes(4),
            origin = PluginOrigin.OFFICIAL,
            trustedKeys = trust,
            host = host,
            enforceFreshness = false
        )
        assertTrue(result is PluginStore.InstallResult.Installed)
        val rec = index.get("starz")!!
        assertEquals(4, rec.activeVersion)
        assertNull(rec.previousVersion)
        assertEquals(PluginStatus.INSTALLED, rec.status)
        assertTrue(File(tmp.root, "starz/versions/4/plugin.json").isFile)
    }

    @Test
    fun updateRetainsPreviousAndRollbackRestores() = runTest {
        val index = FakeIndex()
        val s = store(index)
        s.install(tmp.root, manifestBytes(4), PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        s.install(tmp.root, manifestBytes(5), PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        assertEquals(5, index.get("starz")!!.activeVersion)
        assertEquals(4, index.get("starz")!!.previousVersion)
        val rolled = s.rollback("starz")!!
        assertEquals(4, rolled.activeVersion)
        assertNull(rolled.previousVersion)
        // Both payloads still on disk — rollback moved pointers, never bytes.
        assertTrue(File(tmp.root, "starz/versions/4/plugin.json").isFile)
        assertTrue(File(tmp.root, "starz/versions/5/plugin.json").isFile)
    }

    @Test
    fun tamperedPayloadRejectedWithNothingVisible() = runTest {
        val index = FakeIndex()
        val result = store(index).install(
            tmp.root, "forged {}".toByteArray(), PluginOrigin.OFFICIAL, trust, host
        )
        assertTrue(result is PluginStore.InstallResult.Rejected)
        assertNull(index.get("starz"))
        assertTrue(!File(tmp.root, "starz").exists())
    }

    @Test
    fun interruptedStagingNeverBecomesVisible() = runTest {
        // Crash between staging write and pointer move: staging bytes exist, index empty.
        val staging = File(tmp.root, "starz/.staging").apply { mkdirs() }
        File(staging, "plugin.json").writeBytes(manifestBytes(4))
        val index = FakeIndex()
        assertNull(index.get("starz"))
        assertTrue(!File(tmp.root, "starz/versions/4/plugin.json").exists())
        // A fresh install over the wreckage still converges.
        val result = store(index).install(
            tmp.root, manifestBytes(4), PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false
        )
        assertTrue(result is PluginStore.InstallResult.Installed)
        assertTrue(!File(tmp.root, "starz/.staging").exists())
    }

    @Test
    fun sameVersionDifferentBytesRefused() = runTest {
        val index = FakeIndex()
        val s = store(index)
        val v1a = manifestBytes(4, timeoutMs = 15000)
        val v1b = manifestBytes(4, timeoutMs = 20000)
        s.install(tmp.root, v1a, PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        val result = s.install(tmp.root, v1b, PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        assertTrue(result is PluginStore.InstallResult.Rejected)
        assertEquals(
            ManifestInvalidReason.SCHEMA_VIOLATION,
            (result as PluginStore.InstallResult.Rejected).reason
        )
        // Original bytes untouched.
        assertTrue(File(tmp.root, "starz/versions/4/plugin.json").readBytes().contentEquals(v1a))
    }

    @Test
    fun staleDownloadedPayloadRejected() = runTest {
        val node = PluginTestFixtures.manifestTree {
            it.put("engineApi", 1)
            it.put("issuedAt", "2020-01-01T00:00:00Z")
        }
        val bytes = PluginTestFixtures.signManifest(node, "k1", kp.private)
        val result = store().install(
            tmp.root, bytes, PluginOrigin.OFFICIAL, trust, host, enforceFreshness = true
        )
        assertTrue(result is PluginStore.InstallResult.Rejected)
    }

    @Test
    fun quotaNeverEvictsActiveOrPrevious() = runTest {
        val index = FakeIndex()
        val s = store(index)
        s.install(tmp.root, manifestBytes(4), PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        s.install(tmp.root, manifestBytes(5), PluginOrigin.OFFICIAL, trust, host, enforceFreshness = false)
        // Unreferenced ancient version: evictable.
        File(tmp.root, "starz/versions/2").apply { mkdirs() }
        File(tmp.root, "starz/versions/2/plugin.json").writeBytes(ByteArray(1024))
        val plan = s.evictIfOverBudget(tmp.root, budgetBytes = 1L)
        assertTrue(plan.evict.none { it.version == 4 || it.version == 5 })
        assertTrue(plan.evict.any { it.version == 2 })
        assertTrue(File(tmp.root, "starz/versions/4/plugin.json").isFile)
        assertTrue(File(tmp.root, "starz/versions/5/plugin.json").isFile)
        assertTrue(!File(tmp.root, "starz/versions/2/plugin.json").exists())
    }
}
