package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
import com.exapps.mangaworld.core.source.plugins.PluginUpgradeReconciler
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 3 upgrade reconciliation (§11A): `INCOMPATIBLE` records are rechecked
 * against the new app surface instead of staying declined forever — revived to
 * `AVAILABLE` (opt-in, never auto-serving), never resurrected blindly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginUpgradeReconcilerTest {

    private class FakeIndex : PluginIndexStore {
        val map = mutableMapOf<String, PluginIndexRecord>()
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private val kp = PluginTestFixtures.generateKeyPair()
    private val keys = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public))
    private val trustKeys: PluginTrustKeys = mockk {
        every { current() } returns keys
    }

    private fun signedManifest(engineApi: Int = 1): String {
        val node = PluginTestFixtures.manifestTree {
            it.put("id", "upg")
            it.put("version", 2)
            it.put("engineApi", engineApi)
            it.put("minAppVersion", "9.0.0")
            it.put("issuedAt", Instant.now().toString())
        }
        return PluginTestFixtures.signManifest(node, "k1", kp.private).toString(Charsets.UTF_8)
    }

    private fun reconciler(index: FakeIndex) = PluginUpgradeReconciler(
        index,
        PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined),
        trustKeys,
        kotlinx.coroutines.Dispatchers.Unconfined
    )

    private fun record(status: PluginStatus, manifestJson: String? = signedManifest()) =
        PluginIndexRecord("upg", 2, 1, PluginOrigin.OFFICIAL, status, manifestJson)

    @Test
    fun compatibleOnNewAppBecomesAvailable() = runTest {
        val index = FakeIndex()
        index.put(record(PluginStatus.INCOMPATIBLE))
        // Was declined on 8.x (minApp 9.0.0); rechecked on 9.0.0 → AVAILABLE.
        val revived = reconciler(index).reconcile("9.0.0")
        assertEquals(1, revived)
        assertEquals(PluginStatus.AVAILABLE, index.get("upg")!!.status)
        // AVAILABLE is not serving: no auto-enable, user opts in.
        assertTrue(index.get("upg")!!.activeVersion == 2)
    }

    @Test
    fun stillIncompatibleStays() = runTest {
        val index = FakeIndex()
        // engineApi 99 exceeds every production range, on any app version.
        index.put(record(PluginStatus.INCOMPATIBLE, signedManifest(engineApi = 99)))
        assertEquals(0, reconciler(index).reconcile("9.0.0"))
        assertEquals(PluginStatus.INCOMPATIBLE, index.get("upg")!!.status)
    }

    @Test
    fun nonIncompatibleUntouched() = runTest {
        val index = FakeIndex()
        index.put(record(PluginStatus.ENABLED))
        index.put(record(PluginStatus.REVOKED).copy(id = "other"))
        assertEquals(0, reconciler(index).reconcile("9.0.0"))
        assertEquals(PluginStatus.ENABLED, index.get("upg")!!.status)
        assertEquals(PluginStatus.REVOKED, index.get("other")!!.status)
    }

    @Test
    fun revokedNeverRevived() = runTest {
        val index = FakeIndex()
        index.put(record(PluginStatus.REVOKED))
        // Even a fully valid manifest must not resurrect a revocation.
        assertEquals(0, reconciler(index).reconcile("9.0.0"))
        assertEquals(PluginStatus.REVOKED, index.get("upg")!!.status)
    }

    @Test
    fun missingPayloadSkipped() = runTest {
        val index = FakeIndex()
        index.put(record(PluginStatus.INCOMPATIBLE, null))
        assertEquals(0, reconciler(index).reconcile("9.0.0"))
    }

    @Test
    fun oldAppStaysIncompatible() = runTest {
        val index = FakeIndex()
        index.put(record(PluginStatus.INCOMPATIBLE))
        // Rechecked on 8.10.0 < minApp 9.0.0 → still declined.
        assertEquals(0, reconciler(index).reconcile("8.10.0"))
        assertEquals(PluginStatus.INCOMPATIBLE, index.get("upg")!!.status)
    }

    @Test
    fun staleTrustAnchorStaysIncompatible() = runTest {
        val index = FakeIndex()
        // Signed correctly but issued long ago: the freshness gate (downloaded
        // trust data) refuses resurrection on upgrade, same as on sync.
        val node = PluginTestFixtures.manifestTree {
            it.put("id", "upg")
            it.put("version", 2)
            it.put("engineApi", 1)
            it.put("minAppVersion", "9.0.0")
            it.put("issuedAt", "2020-01-01T00:00:00Z")
        }
        val stale = PluginTestFixtures.signManifest(node, "k1", kp.private).toString(Charsets.UTF_8)
        index.put(record(PluginStatus.INCOMPATIBLE, stale))
        assertEquals(0, reconciler(index).reconcile("9.0.0"))
        assertEquals(PluginStatus.INCOMPATIBLE, index.get("upg")!!.status)
    }
}
