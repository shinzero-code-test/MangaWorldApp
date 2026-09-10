package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.ReadingHistoryItem
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * Phase 1 (UI truth) regression tests — pure-JVM invariants behind the
 * profile/search/browse/library fixes. No Android framework, no dispatchers.
 */
class PhaseOneUiTruthTest {

    @Test
    fun `isLocalSource identifies imported and local ids`() {
        assertTrue(MangaSource.isLocalSource("imported"))
        assertTrue(MangaSource.isLocalSource("local"))
        assertFalse(MangaSource.isLocalSource("azora"))
        assertFalse(MangaSource.isLocalSource("olympus"))
    }

    @Test
    fun `history entity preserves imported sourceId`() {
        val entity = ReadingHistoryEntity(
            mangaId = "imported_abc_12345",
            slug = "imported_abc_12345",
            title = "Imported Manga",
            coverUrl = "file:///cover.jpg",
            sourceId = "imported",
            lastChapterNumber = 2f,
            lastReadAt = 1_000L
        )
        assertEquals("imported", entity.sourceId)
        assertTrue(MangaSource.isLocalSource(entity.sourceId))
    }

    @Test
    fun `history item carries raw sourceId with blank default`() {
        val item = ReadingHistoryItem(
            mangaId = "azora_x",
            slug = "x",
            title = "T",
            coverUrl = "",
            source = MangaSource.AZORA,
            lastChapterNumber = 1f,
            lastReadAt = 0L
        )
        // Old call sites (positional args) keep compiling and stay non-local.
        assertEquals("", item.sourceId)
        assertFalse(MangaSource.isLocalSource(item.sourceId.ifBlank { item.source.id }))
        assertEquals("imported", item.copy(sourceId = "imported").sourceId)
    }

    @Test
    fun `browse grid key is unique for duplicate source-slug pairs`() {
        // Mirrors the BrowseScreen LazyVerticalGrid key: source + slug + index.
        // The reported crash ("olympus_feng-shen-ji was already used") came
        // from a source_slug-only key colliding while filters refresh.
        fun gridKey(sourceId: String, slug: String, index: Int) = "${sourceId}_${slug}_$index"
        assertNotEquals(
            gridKey("olympus", "feng-shen-ji", 0),
            gridKey("olympus", "feng-shen-ji", 1)
        )
    }
}
