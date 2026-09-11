package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.data.local.HomeCacheCodec
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 9: HomeCacheCodec round-trip — pure JVM, no Android, no Room.
 */
class HomeCacheCodecTest {

    private fun manga(id: String) = MangaItem(
        id = id, slug = "slug-$id", title = "Manga $id",
        coverUrl = "https://example.com/c.jpg", source = MangaSource.AZORA,
        rating = 4.5f, latestChapter = 12, totalChapters = 20,
        lastUpdated = 1_000L, isNew = true, url = "https://example.com/m"
    )

    private fun latest(id: String) = LatestChapterItem(
        mangaId = id, mangaSlug = "slug-$id", mangaTitle = "Manga $id",
        coverUrl = "https://example.com/c.jpg", chapterNumber = 3.5f,
        chapterTitle = "Ch title", chapterUrl = "https://example.com/ch",
        timeAgo = "2h", publishedAt = 2_000L, source = MangaSource.OLYMPUS, isNew = true
    )

    @Test
    fun roundTrip_preservesAllSections() {
        val data = HomeData(
            featured = listOf(manga("f1")),
            latestChapters = listOf(latest("l1")),
            trending = listOf(manga("t1"), manga("t2"))
        )
        val decoded = HomeCacheCodec.decode(HomeCacheCodec.encode(data))!!
        assertEquals(listOf("f1"), decoded.featured.map { it.id })
        assertEquals(listOf("l1"), decoded.latestChapters.map { it.mangaId })
        assertEquals(listOf("t1", "t2"), decoded.trending.map { it.id })
        val item = decoded.featured.first()
        assertEquals("Manga f1", item.title)
        assertEquals(MangaSource.AZORA, item.source)
        assertEquals(4.5f, item.rating)
        assertEquals(12, item.latestChapter)
        assertEquals(true, item.isNew)
        val ch = decoded.latestChapters.first()
        assertEquals(3.5f, ch.chapterNumber)
        assertEquals("Ch title", ch.chapterTitle)
        assertEquals(MangaSource.OLYMPUS, ch.source)
    }

    @Test
    fun decode_dropsUnknownSourcesAndRejectsGarbage() {
        assertNull(HomeCacheCodec.decode("{not json"))
        assertNull(HomeCacheCodec.decode(""))
        // Hand-built doc with an unknown source id: item dropped, doc survives.
        val raw = """{"featured":[{"id":"x","slug":"x","title":"X","coverUrl":"","sourceId":"nope"}],"latest":[],"trending":[]}"""
        val decoded = HomeCacheCodec.decode(raw)!!
        assertTrue(decoded.featured.isEmpty())
    }

    @Test
    fun encode_capsSectionSize() {
        val many = (0 until 200).map { manga("m$it") }
        val decoded = HomeCacheCodec.decode(
            HomeCacheCodec.encode(HomeData(featured = many))
        )!!
        assertEquals(HomeCacheCodec.MAX_ITEMS_PER_SECTION, decoded.featured.size)
    }
}
