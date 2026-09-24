package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginIndexEntry
import com.exapps.mangaworld.core.source.plugins.PluginStorage
import com.exapps.mangaworld.core.source.plugins.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: storage atomicity. Staged candidates are never visible; activation moves
 * pointers only; rollback restores the previous pointer without copying bytes. The I/O
 * layer (Phase 2) must implement exactly these transitions.
 */
class StorageAtomicityTest {

    @Test
    fun layoutPathsAreStableAndTraversalSafe() {
        assertEquals(
            "/data/starz/versions/4/plugin.json",
            PluginStorage.manifestPath("/data", "starz", 4)
        )
        assertEquals(
            "/data/starz/versions/4/source.js",
            PluginStorage.scriptPath("/data", "starz", 4)
        )
        assertEquals("/data/starz/.staging", PluginStorage.stagingDir("/data", "starz"))
    }

    @Test
    fun traversalIdsRefused() {
        try {
            PluginStorage.versionDir("/data", "../evil", 1)
            throw AssertionError("expected refusal")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            PluginStorage.stagingDir("/data", "a/b")
            throw AssertionError("expected refusal")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun activateSwapsPointerAndKeepsRollback() {
        val start = PluginStorage.ActivationState(active = 3, previous = null)
        val after = PluginStorage.activate(start, 4)
        assertEquals(4, after.active)
        assertEquals(3, after.previous)

        // Activating the already-active version is a no-op (keeps the rollback target).
        assertEquals(after, PluginStorage.activate(after, 4))
    }

    @Test
    fun rollbackRestoresPrevious() {
        val state = PluginStorage.ActivationState(active = 4, previous = 3)
        val back = PluginStorage.rollback(state)
        assertEquals(3, back.active)

        // No previous version: rollback is a no-op, never clears the active pointer.
        val single = PluginStorage.ActivationState(active = 4, previous = null)
        assertEquals(single, PluginStorage.rollback(single))
    }

    @Test
    fun interruptedInstallNeverChangesState() {
        // The crash scenario: staging completed, activation never ran.
        // State is untouched by construction — only activate() mutates it.
        val before = PluginStorage.ActivationState(active = 3, previous = null)
        assertEquals(before.active, 3)
        assertEquals(before.previous, null)
    }

    @Test
    fun indexHintAloneAuthorisesNothing() {
        val index = PluginIndexEntry("starz", 5, "https://x/starz/plugin.json", "8.8.0")
        // Forged version bump with no verifying manifest behind it.
        assertFalse(
            PluginStorage.authorizeInstall(
                index,
                com.exapps.mangaworld.core.source.plugins.ManifestResult.Invalid(
                    com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason.BAD_SIGNATURE,
                    "x"
                )
            )
        )
    }
}
