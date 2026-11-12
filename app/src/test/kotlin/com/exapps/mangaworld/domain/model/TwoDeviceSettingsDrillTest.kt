package com.exapps.mangaworld.domain.model

import com.exapps.mangaworld.core.firebase.FirebaseSyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-33 / p3-next section 3: the two-device settings-revert drill from the
 * rollout checklist. Two devices, one cloud doc.
 *
 * The production rule is a last-writer-wins guard on the settings doc: a stale
 * device must not revert the newer settings of the device that wrote last. The
 * predicate lives in FirebaseSyncMerge.shouldApplySettings; this test pins the
 * INTERACTION (two devices, one cloud doc, both orders) rather than the
 * predicate alone. The predicate's truth table is pinned by SettingsGuardTest.
 */
class TwoDeviceSettingsDrillTest {

    /** One device's view of the settings doc. */
    private class Device(val name: String, var appliedAt: Long)

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
    fun staleDeviceDoesNotRevertNewerSettings() {
        // Device A edited at t=200 (its local write is newer).
        val deviceA = Device("A", appliedAt = 200)
        // Device B (stale, applied at t=100) pushed its old settings to the
        // cloud at t=100, so the cloud doc is now OLDER than A's.
        val cloudDocUpdatedAt = 100L

        val applied = applyCloudDoc(deviceA, cloudDocUpdatedAt)

        assertTrue("the stale cloud doc must not overwrite newer local settings", !applied)
        assertEquals(200L, deviceA.appliedAt)
    }

    @Test
    fun newerCloudDocIsAppliedByTheOtherDevice() {
        val deviceB = Device("B", appliedAt = 100)
        val cloudDocUpdatedAt = 200L

        val applied = applyCloudDoc(deviceB, cloudDocUpdatedAt)

        assertTrue("the newer cloud doc must be applied", applied)
        assertEquals(200L, deviceB.appliedAt)
    }

    @Test
    fun equalTimestampIsApplied() {
        // Last writer wins, and an equal watermark re-applies (idempotent push).
        val deviceC = Device("C", appliedAt = 150)

        val applied = applyCloudDoc(deviceC, 150L)

        assertTrue(applied)
        assertEquals(150L, deviceC.appliedAt)
    }

    @Test
    fun drillMatchesTheProductionPredicate() {
        // The drill must not drift from the predicate the app actually uses.
        val docs = listOf(0L, 99L, 100L, 101L, 200L)
        val applieds = listOf(0L, 100L, 200L)
        for (doc in docs) {
            for (applied in applieds) {
                assertEquals(
                    "doc=$doc applied=$applied",
                    FirebaseSyncMerge.shouldApplySettings(doc, applied),
                    shouldApply(doc, applied),
                )
            }
        }
    }
}
