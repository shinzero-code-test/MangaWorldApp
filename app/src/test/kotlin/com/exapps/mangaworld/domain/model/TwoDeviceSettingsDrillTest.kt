package com.exapps.mangaworld.domain.model

import com.exapps.mangaworld.core.firebase.FirebaseSyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-33 / p3-next §3 (the "two-device settings drill" from the rollout
 * checklist): two devices, one cloud doc.
 *
 * The production rule is a last-writer-wins guard on the *settings* doc: a
 * stale device must not revert the newer settings of the device that wrote
 * last. The predicate lives in [FirebaseSyncMerge.shouldApplySettings]; this
 * test pins the INTERACTION (two stores, one cloud doc, both orders) rather
 * than the predicate alone — the predicate's truth table is pinned by
 * `SettingsGuardTest`.
 */
class TwoDeviceSettingsDrillTest {

    /** One device's view of the settings doc. */
    private data class Device(val name: String, var appliedAt: Long)

    /**
     * The shared rule: a device applies the cloud doc only when the doc is at
     * least as new as what the device has already applied.
     */
    private fun applyCloudDoc(device: Device, docUpdatedAt: Long): Boolean {
        if (!shouldApply(docUpdatedAt, device.appliedAt)) return false
        device.appliedAt = docUpdatedAt
        return true
    }

    private fun shouldApply(docUpdatedAt: Long, appliedAt: Long): Boolean =
        docUpdatedAt >= appliedAt

    @Test
    fun `a stale device does not revert newer settings`() {
        // Device A edits at t=200 (its local write is newer).
        val deviceA = Device("A", appliedAt = 200)
        // Device B (stale, applied t=100) pushes its old settings to the cloud
        // at t=100 — the cloud doc is now OLDER than A's.
        val cloudDocUpdatedAt = 100L

        // A pulls the cloud doc: it is older than what A already applied →
        // A keeps its own newer settings.
        assertTrue(
            "the stale cloud doc must not overwrite the newer local settings",
            !applyCloudDoc(deviceA, cloudDocUpdatedAt),
        )
        assertEquals(200L, deviceA.appliedAt)
    }

    @Test
    fun `a newer cloud doc is applied by the other device`() {
        val deviceB = Device("B", appliedAt = 100)
        val cloudDocUpdatedAt = 200L

        assertTrue(
            "the newer cloud doc must be applied",
            applyCloudDoc(deviceB, cloudDocUpdatedAt),
        )
        assertEquals(200L, deviceB.appliedAt)
    }

    @Test
    fun `an equal timestamp is applied (last writer wins, no data loss)` {
        val deviceC = Device("C", appliedAt = 150)
        assertTrue(applyCloudDoc(deviceC, 150L))
        assertEquals(150L, deviceC.appliedAt)
    }

    @Test
    fun `the drill matches the production predicate`() {
        // The drill must not drift from the predicate the app actually uses.
        for (doc in listOf(0L, 99L, 100L, 101L, 200L)) {
            for (applied in listOf(0L, 100L, 200L)) {
                assertEquals(
                    "doc=$doc applied=$applied",
                    FirebaseSyncMerge.shouldApplySettings(doc, applied),
                    shouldApply(doc, applied),
                )
            }
        }
    }
}
