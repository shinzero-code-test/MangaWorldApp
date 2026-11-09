package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.ReadingPositionSyncManager
import com.exapps.mangaworld.core.data.local.SyncTombstone
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-11/U-12 — tombstone predicates + push guards.
 *
 * Restore-vs-resurrect and position newest-wins go through the pure
 * [FirebaseSyncMerge] predicates the sync/restore paths delegate to.
 * No MockK, no Firestore, no Room — the predicates are the logic under test.
 */
class SyncTombstoneTest {

    private fun annotation(updatedAt: Long = 100L) = ReaderAnnotationEntity(
        mangaId = "m1",
        chapterUrl = "http://example/ch1",
        pageIndex = 0,
        note = "note",
        isBookmarked = true,
        updatedAt = updatedAt
    )

    private fun tombstonesOf(vararg tombstones: SyncTombstone): Map<String, SyncTombstone> =
        FirebaseSyncMerge.newestTombstones(tombstones.toList())

    // ─── isTombstoned — equal timestamps suppress ─────────────────────────

    @Test
    fun tombstone_newerThanRow_suppresses() {
        val map = tombstonesOf(SyncTombstone("favorites", "m1", deletedAt = 200L))
        assertTrue(FirebaseSyncMerge.isTombstoned("favorites", "m1", 100L, map))
    }

    @Test
    fun tombstone_equalToRow_suppresses() {
        // deletedAt >= updatedAt — the tombstone wins ties (restore≠resurrect).
        val map = tombstonesOf(SyncTombstone("favorites", "m1", deletedAt = 100L))
        assertTrue(FirebaseSyncMerge.isTombstoned("favorites", "m1", 100L, map))
    }

    @Test
    fun tombstone_olderThanRow_keeps() {
        val map = tombstonesOf(SyncTombstone("favorites", "m1", deletedAt = 50L))
        assertFalse(FirebaseSyncMerge.isTombstoned("favorites", "m1", 100L, map))
    }

    @Test
    fun tombstone_missing_keeps() {
        assertFalse(FirebaseSyncMerge.isTombstoned("favorites", "m1", 100L, emptyMap()))
    }

    // ─── Annotation dual-scheme (FS-9) ────────────────────────────────────

    @Test
    fun annotation_legacyTombstone_suppresses() {
        // The old pull only checked the new scheme; legacy deletions came back.
        val entity = annotation(updatedAt = 100L)
        val map = tombstonesOf(
            SyncTombstone("readerAnnotations", FirebaseSyncMerge.legacyAnnotationDocId(entity), deletedAt = 200L)
        )
        assertTrue(FirebaseSyncMerge.isAnnotationTombstoned(entity, map))
    }

    @Test
    fun annotation_newTombstone_suppresses() {
        val entity = annotation(updatedAt = 100L)
        val map = tombstonesOf(
            SyncTombstone("readerAnnotations", FirebaseSyncMerge.annotationDocId(entity), deletedAt = 200L)
        )
        assertTrue(FirebaseSyncMerge.isAnnotationTombstoned(entity, map))
    }

    @Test
    fun annotation_olderTombstone_keeps() {
        val entity = annotation(updatedAt = 100L)
        val map = tombstonesOf(
            SyncTombstone("readerAnnotations", FirebaseSyncMerge.legacyAnnotationDocId(entity), deletedAt = 50L)
        )
        assertFalse(FirebaseSyncMerge.isAnnotationTombstoned(entity, map))
    }

    // ─── newestTombstones — newest per key wins ───────────────────────────

    @Test
    fun newestTombstones_picksMaxDeletedAt() {
        val map = tombstonesOf(
            SyncTombstone("favorites", "m1", deletedAt = 100L),
            SyncTombstone("favorites", "m1", deletedAt = 300L),
            SyncTombstone("favorites", "m2", deletedAt = 50L)
        )
        assertEquals(300L, map["favorites|m1"]!!.deletedAt)
        assertEquals(50L, map["favorites|m2"]!!.deletedAt)
    }

    // ─── FS-1 push guard (entity rows) ────────────────────────────────────

    @Test
    fun shouldPushLocal_newer_pushes() {
        assertTrue(FirebaseSyncMerge.shouldPushLocal(localTs = 200L, remoteTs = 100L))
    }

    @Test
    fun shouldPushLocal_equal_keepsCloud() {
        assertFalse(FirebaseSyncMerge.shouldPushLocal(localTs = 100L, remoteTs = 100L))
    }

    @Test
    fun shouldPushLocal_stale_skips() {
        assertFalse(FirebaseSyncMerge.shouldPushLocal(localTs = 50L, remoteTs = 100L))
    }

    @Test
    fun shouldPushLocal_absentRemote_pushes() {
        assertTrue(FirebaseSyncMerge.shouldPushLocal(localTs = 50L, remoteTs = null))
    }

    // ─── U-12 position push guard (mirror of pull) ────────────────────────

    @Test
    fun shouldPushPosition_newer_pushes() {
        assertTrue(ReadingPositionSyncManager.shouldPushPosition(200L, 100L))
    }

    @Test
    fun shouldPushPosition_stale_skips() {
        assertFalse(ReadingPositionSyncManager.shouldPushPosition(50L, 100L))
    }

    @Test
    fun shouldPushPosition_equal_keepsCloud() {
        assertFalse(ReadingPositionSyncManager.shouldPushPosition(100L, 100L))
    }

    @Test
    fun shouldPushPosition_absentRemote_pushes() {
        assertTrue(ReadingPositionSyncManager.shouldPushPosition(50L, null))
    }

    @Test
    fun positionNewestWins_byTimestampNotPushOrder() {
        // Device A pushes at t=200; device B's stale snapshot (t=100) pushes
        // later — the guard must still skip B's older value.
        assertFalse(ReadingPositionSyncManager.shouldPushPosition(100L, 200L))
        assertTrue(ReadingPositionSyncManager.shouldPushPosition(200L, 100L))
    }
}
