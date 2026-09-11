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

    // ─── Item 6: pull-deserialization parsers ────────────────────────────────

    private fun historyDoc(
        id: String = "azora_x",
        fields: Map<String, Any?> = mapOf(
            "mangaId" to "azora_x",
            "slug" to "x",
            "title" to "Imported T",
            "coverUrl" to "file:///c.jpg",
            "sourceId" to "imported",
            "lastChapterNumber" to 2.0,
            "lastChapterUrl" to "ch-2",
            "lastReadAt" to 9_000L,
            "readChapters" to 2L,
            "totalChapters" to 10L,
            "durationMs" to 60_000L
        )
    ): com.google.firebase.firestore.DocumentSnapshot {
        val doc = io.mockk.mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        io.mockk.every { doc.id } returns id
        fields.forEach { (k, v) ->
            when (v) {
                is String -> io.mockk.every { doc.getString(k) } returns v
                is Long -> io.mockk.every { doc.getLong(k) } returns v
                is Double -> io.mockk.every { doc.getDouble(k) } returns v
                is Boolean -> io.mockk.every { doc.getBoolean(k) } returns v
            }
        }
        return doc
    }

    @org.junit.Test
    fun pullHistoryParser_readsImportedEntry() {
        val entity = FirebaseSyncMerge.history(historyDoc())!!
        org.junit.Assert.assertEquals("azora_x", entity.mangaId)
        org.junit.Assert.assertEquals("imported", entity.sourceId)
        org.junit.Assert.assertEquals(2f, entity.lastChapterNumber)
        org.junit.Assert.assertEquals(9_000L, entity.lastReadAt)
        org.junit.Assert.assertEquals("file:///c.jpg", entity.coverUrl)
    }

    @org.junit.Test
    fun pullHistoryParser_fallsBackToDocIdAndRejectsBlank() {
        val blank = historyDoc(id = "azora_y", fields = mapOf("title" to "T"))
        org.junit.Assert.assertEquals("azora_y", FirebaseSyncMerge.history(blank)!!.mangaId)
        val noId = historyDoc(id = "", fields = emptyMap())
        org.junit.Assert.assertNull(FirebaseSyncMerge.history(noId))
    }

    @org.junit.Test
    fun pullFavoriteParser_readsEntryWithDefaults() {
        val doc = io.mockk.mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        io.mockk.every { doc.id } returns "lekmanga_m"
        io.mockk.every { doc.getString("sourceId") } returns "lekmanga"
        io.mockk.every { doc.getLong("addedAt") } returns 5_000L
        val entity = FirebaseSyncMerge.favorite(doc)!!
        org.junit.Assert.assertEquals("lekmanga_m", entity.mangaId)
        org.junit.Assert.assertEquals("lekmanga", entity.sourceId)
        org.junit.Assert.assertEquals(5_000L, entity.addedAt)
        org.junit.Assert.assertTrue(entity.isFavorite)
    }

    @org.junit.Test
    fun pullAnnotationParser_requiresIdentity() {
        val doc = io.mockk.mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        io.mockk.every { doc.getString("mangaId") } returns "m1"
        io.mockk.every { doc.getString("chapterUrl") } returns "ch"
        io.mockk.every { doc.getLong("pageIndex") } returns 3L
        val entity = FirebaseSyncMerge.annotation(doc)!!
        org.junit.Assert.assertEquals(3, entity.pageIndex)
        val missing = io.mockk.mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        io.mockk.every { missing.getString(any()) } returns null
        org.junit.Assert.assertNull(FirebaseSyncMerge.annotation(missing))
    }
}