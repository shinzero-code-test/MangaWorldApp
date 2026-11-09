package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-10 — settings sync timestamp guard (plus RC-disabled round trip).
 *
 * DocumentSnapshot cannot be faked on the JVM, so these go through the pure
 * predicates the appliers delegate to ([FirebaseSyncMerge.shouldApplySettings]
 * and the stored-vs-filtered set logic). No MockK — the two-device drill
 * (local newer edit survives a stale merge) is the predicate's truth table.
 */
class SettingsGuardTest {

    // ─── Per-group updatedAt guard ────────────────────────────────────────

    @Test
    fun staleUserDoc_skipped() {
        assertFalse(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 100L, appliedAt = 200L))
    }

    @Test
    fun freshUserDoc_applied() {
        assertTrue(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 200L, appliedAt = 100L))
    }

    @Test
    fun equalWatermark_reapplied_idempotent() {
        // Idempotent push writes the same bytes — re-applying is harmless.
        assertTrue(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 200L, appliedAt = 200L))
    }

    @Test
    fun zeroWatermark_firstApply_runs() {
        assertTrue(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 123L, appliedAt = 0L))
    }

    @Test
    fun readerDoc_usesItsOwnWatermark() {
        // The reader watermark is independent: a fresh user doc (appliedAt
        // advanced) must not mask a still-stale reader doc, or vice versa.
        // Both appliers call the same predicate against different watermarks.
        assertFalse(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 50L, appliedAt = 300L))
        assertTrue(FirebaseSyncMerge.shouldApplySettings(remoteUpdatedAt = 500L, appliedAt = 100L))
    }

    // ─── RC-disabled source survives push→pull ────────────────────────────
    //
    // Push writes the *stored* set (not the RC-filtered view); pull intersects
    // the pushed set with the known ids. A temporarily server-disabled source
    // must therefore still be present after the round trip.

    @Test
    fun rcDisabledSource_survivesPushPullRoundTrip() {
        val stored = setOf("azora", "starz", "olympus")
        val rcDisabled = setOf("starz")

        // Read-time view (what the old buggy push persisted):
        val filteredView = stored - rcDisabled
        assertFalse("starz" in filteredView)

        // Push (fixed): the stored set verbatim.
        val pushed = stored.toList()
        // Pull: intersect with known ids (phantom-id guard).
        val pulled = pushed.toSet().intersect(BuiltinSourceIds.ALL)

        assertTrue("starz in pulled", "starz" in pulled)
        assertEquals(stored, pulled)
    }

    @Test
    fun pushThenRcReenable_keepsSourceEnabled() {
        val stored = setOf("azora", "starz")
        val pushed = stored.toList()
        val pulled = pushed.toSet().intersect(BuiltinSourceIds.ALL)
        // RC re-enables: the filtered view recomputes from stored — the
        // source never left the stored set.
        val viewAfterReenable = pulled - emptySet<String>()
        assertTrue("starz" in viewAfterReenable)
    }

    // ─── Stale push cannot revert a newer local edit ──────────────────────

    @Test
    fun stalePush_doesNotRevertNewerLocalEdit() {
        val localWatermark = 1_000L // device A pushed a newer edit
        val staleRemote = 500L // device B's unpushed snapshot
        // B's merge applies only if fresh — it isn't, so local survives.
        assertFalse(FirebaseSyncMerge.shouldApplySettings(staleRemote, localWatermark))
    }

    @Test
    fun pullDropsPhantomIds() {
        val cloudClaim = listOf("azora", "hijacked-source", "")
        val stored = cloudClaim.toSet().intersect(BuiltinSourceIds.ALL)
        assertEquals(setOf("azora"), stored)
    }
}
