package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.MadaraBaseScraper
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Through-the-scraper Madara coverage (F1/F2): every assertion drives the
 * REAL [MadaraBaseScraper] parser over a realistic captured-style fixture —
 * lazy `data-src` covers, `data:` placeholders, Arabic chapter labels with
 * trailing noise, Arabic dates. A selector/regex drift that breaks the 7
 * Madara-family sources fails here instead of passing silently.
 *
 * Fixture acts as LEKMANGA (mangalik.net), the migrated-domain Madara source.
 */
class MadaraFamilyFixtureTest {

    private val scraper = MadaraBaseScraper(
        OkHttpClient(),
        MangaSource.LEKMANGA,
        mockk<SettingsRepository>(relaxed = true)
    )

    private fun detailDoc() = Jsoup.parse(
        javaClass.getResource("/scrapers/madara_detail_sample.html")!!.readText(),
        "https://mangalik.net"
    )

    @Test
    fun `real parser extracts all chapters from fixture`() {
        val chapters = detailDoc().select(".listing-chapters_wrap li")
            .mapNotNull { scraper.parseChapterLi(it, "one-piece") }

        assertEquals(3, chapters.size)
    }

    @Test
    fun `first number wins over trailing noise through real parser`() {
        val chapters = detailDoc().select(".listing-chapters_wrap li")
            .mapNotNull { scraper.parseChapterLi(it, "one-piece") }
            .associateBy { it.number }

        // "الفصل 12 : 3 وحوش" must stay 12, not 123 (the old gluing bug).
        assertNotNull(chapters[12f])
        assertNull(chapters[123f])
        assertNotNull(chapters[11.5f])
    }

    @Test
    fun `chapter hrefs resolve absolute through real parser`() {
        val chapters = detailDoc().select(".listing-chapters_wrap li")
            .mapNotNull { scraper.parseChapterLi(it, "one-piece") }

        assertTrue(chapters.all { it.url.startsWith("https://mangalik.net/manga/one-piece/") })
        assertTrue(chapters.all { it.mangaId == "lekmanga_one-piece" })
    }

    @Test
    fun `arabic dates parse through real parser`() {
        val chapters = detailDoc().select(".listing-chapters_wrap li")
            .mapNotNull { scraper.parseChapterLi(it, "one-piece") }
            .sortedByDescending { it.number }

        assertEquals("12 يناير 2024", chapters[0].dateText)
        assertNotNull(chapters[0].date)
        assertTrue(chapters[0].date!! > 0L)
    }

    @Test
    fun `chapter title strips the arabic prefix through real parser`() {
        val chapters = detailDoc().select(".listing-chapters_wrap li")
            .mapNotNull { scraper.parseChapterLi(it, "one-piece") }
            .associateBy { it.number }

        // "الفصل 12 : 3 وحوش" → title keeps the non-numeric residue, prefix gone.
        val title = chapters[12f]?.title.orEmpty()
        assertTrue(!title.contains("الفصل"))
    }
}
