package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
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
        assertTrue(BuiltinSourceIds.isLocal("imported"))
        assertTrue(BuiltinSourceIds.isLocal("local"))
        assertFalse(BuiltinSourceIds.isLocal("azora"))
        assertFalse(BuiltinSourceIds.isLocal("olympus"))
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
        assertTrue(BuiltinSourceIds.isLocal(entity.sourceId))
    }

    @Test
    fun `history item carries raw sourceId with blank default`() {
        val item = ReadingHistoryItem(
            mangaId = "azora_x",
            slug = "x",
            title = "T",
            coverUrl = "",
            source = SourceId("azora"),
            lastChapterNumber = 1f,
            lastReadAt = 0L
        )
        // Old call sites (positional args) keep compiling and stay non-local.
        assertEquals("", item.sourceId)
        assertFalse(BuiltinSourceIds.isLocal(item.sourceId.ifBlank { item.source.value }))
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
