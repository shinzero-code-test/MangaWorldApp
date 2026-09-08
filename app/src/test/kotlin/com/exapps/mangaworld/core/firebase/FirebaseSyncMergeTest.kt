package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseSyncMergeTest {

    @Test
    fun historyKeepsNewerLocalRecordForDuplicateManga() {
        val local = history(lastReadAt = 200L, title = "Local")
        val remote = history(lastReadAt = 100L, title = "Remote")

        val merged = FirebaseSyncMerge.history(listOf(local), listOf(remote))

        assertEquals(listOf(local), merged)
    }

    @Test
    fun historyKeepsNewerRemoteRecordForDuplicateManga() {
        val local = history(lastReadAt = 100L, title = "Local")
        val remote = history(lastReadAt = 200L, title = "Remote")

        val merged = FirebaseSyncMerge.history(listOf(local), listOf(remote))

        assertEquals(listOf(remote), merged)
    }

    @Test
    fun annotationKeepsLocalRecordWhenTimestampsMatch() {
        val local = ReaderAnnotationEntity("manga", "chapter", 1, "Local", true, 100L)
        val remote = ReaderAnnotationEntity("manga", "chapter", 1, "Remote", false, 100L)

        val merged = FirebaseSyncMerge.annotations(listOf(local), listOf(remote))

        assertEquals(listOf(local), merged)
    }

    @Test
    fun favoritesKeepsNewerRemoteRecordForDuplicateManga() {
        val local = favorite("m2", addedAt = 50L)
        val remote = favorite("m2", addedAt = 200L)

        assertEquals(listOf(remote), FirebaseSyncMerge.favorites(listOf(local), listOf(remote)))
    }

    @Test
    fun favoritesKeepsNewerLocalRecordForDuplicateManga() {
        val local = favorite("m2", addedAt = 200L)
        val remote = favorite("m2", addedAt = 50L)

        assertEquals(listOf(local), FirebaseSyncMerge.favorites(listOf(local), listOf(remote)))
    }

    @Test
    fun favoritesUnionDisjointKeys() {
        val local = favorite("m1", addedAt = 100L)
        val remote = favorite("m3", addedAt = 10L)

        val merged = FirebaseSyncMerge.favorites(listOf(local), listOf(remote))

        assertEquals(setOf("m1", "m3"), merged.map { it.mangaId }.toSet())
    }

    @Test
    fun favoritesTieKeepsLocalRecord() {
        // Tie-break is order-dependent (local concatenated first): pin it so a
        // refactor of the concat order fails loudly instead of silently flipping.
        val local = favorite("m2", addedAt = 100L, title = "Local")
        val remote = favorite("m2", addedAt = 100L, title = "Remote")

        assertEquals(listOf(local), FirebaseSyncMerge.favorites(listOf(local), listOf(remote)))
    }

    @Test
    fun annotationsKeepsNewerRemoteRecord() {
        val local = ReaderAnnotationEntity("manga", "chapter", 1, "Local", true, 100L)
        val remote = ReaderAnnotationEntity("manga", "chapter", 1, "Remote", false, 200L)

        assertEquals(listOf(remote), FirebaseSyncMerge.annotations(listOf(local), listOf(remote)))
    }

    @Test
    fun emptyInputsMergeToEmpty() {
        assertTrue(FirebaseSyncMerge.favorites(emptyList(), emptyList()).isEmpty())
        assertTrue(FirebaseSyncMerge.history(emptyList(), emptyList()).isEmpty())
        assertTrue(FirebaseSyncMerge.annotations(emptyList(), emptyList()).isEmpty())
    }

    private fun favorite(mangaId: String, addedAt: Long, title: String = "Title") =
        com.exapps.mangaworld.core.data.local.entity.FavoriteEntity(
            mangaId = mangaId,
            slug = "slug-$mangaId",
            title = title,
            coverUrl = "",
            sourceId = "azora",
            addedAt = addedAt
        )

    private fun history(lastReadAt: Long, title: String) = ReadingHistoryEntity(
        mangaId = "manga",
        slug = "slug",
        title = title,
        coverUrl = "",
        sourceId = "source",
        lastChapterNumber = 1f,
        lastChapterUrl = "chapter",
        lastReadAt = lastReadAt,
        readChapters = 1,
        totalChapters = 1
    )
}
