package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.ScraperText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ScraperText — the shared parsing helpers actually used by
 * production scrapers (unlike fixture-only selector tests).
 */
class ScraperTextTest {

    // ── firstChapterNumber ────────────────────────────────────────────────────

    @Test
    fun `first number wins over trailing noise`() {
        // The old strip-non-digits logic concatenated this to 123.0.
        assertEquals(12f, ScraperText.firstChapterNumber("الفصل 12 : 3 وحوش"))
    }

    @Test
    fun `plain arabic chapter label`() {
        assertEquals(18f, ScraperText.firstChapterNumber("الفصل 18"))
    }

    @Test
    fun `decimal chapters survive`() {
        assertEquals(12.5f, ScraperText.firstChapterNumber("Chapter 12.5"))
    }

    @Test
    fun `leading noise is skipped to the first real number`() {
        assertEquals(7f, ScraperText.firstChapterNumber("فصل خاص #7"))
    }

    @Test
    fun `english label parses`() {
        assertEquals(101f, ScraperText.firstChapterNumber("Chapter 101"))
    }

    @Test
    fun `null blank and numberless input yield null`() {
        assertNull(ScraperText.firstChapterNumber(null))
        assertNull(ScraperText.firstChapterNumber(""))
        assertNull(ScraperText.firstChapterNumber("   "))
        assertNull(ScraperText.firstChapterNumber("إضافات"))
    }

    // ── lastSegmentNumber ─────────────────────────────────────────────────────

    @Test
    fun `last segment number ignores query`() {
        assertEquals(33f, ScraperText.lastSegmentNumber("https://x.com/manga/abc/33?page=2"))
    }

    @Test
    fun `trailing slash tolerated`() {
        assertEquals(5f, ScraperText.lastSegmentNumber("https://x.com/series/abc/5/"))
    }

    @Test
    fun `non numeric segment yields null`() {
        assertNull(ScraperText.lastSegmentNumber("https://x.com/manga/one-piece"))
    }

    // ── slugFromHref ──────────────────────────────────────────────────────────

    @Test
    fun `manga layout slug`() {
        assertEquals("solo-leveling", ScraperText.slugFromHref("https://site.com/manga/solo-leveling/"))
    }

    @Test
    fun `comics and manhwa layouts produce real slugs not full urls`() {
        assertEquals("some-manhwa", ScraperText.slugFromHref("https://lekmanga.online/comics/some-manhwa"))
        assertEquals("leko-title", ScraperText.slugFromHref("https://mangaleko.com/manhwa/leko-title/?param=1"))
    }

    @Test
    fun `query string stripped`() {
        assertEquals("title", ScraperText.slugFromHref("https://site.com/manga/title?utm=x"))
    }

    @Test
    fun `pagination segments are rejected`() {
        assertNull(ScraperText.slugFromHref("https://site.com/comics/page/2"))
    }

    @Test
    fun `bare slug works`() {
        assertEquals("title", ScraperText.slugFromHref("https://site.com/title/"))
    }

    // ── extractViews (LOW-2: K/M suffixes) ────────────────────────────────────

    @Test
    fun `plain arabic views`() {
        assertEquals(1234L, ScraperText.extractViews("1,234 مشاهدة"))
    }

    @Test
    fun `k-suffix english views`() {
        assertEquals(5600L, ScraperText.extractViews("5.6K views"))
    }

    @Test
    fun `m-suffix views`() {
        assertEquals(2000000L, ScraperText.extractViews("2M مشاهدة"))
    }

    @Test
    fun `views without match returns null`() {
        assertNull(ScraperText.extractViews(null))
        assertNull(ScraperText.extractViews(""))
        assertNull(ScraperText.extractViews("لا توجد مشاهدات بعد"))
    }

    // ── parseArabicDate ───────────────────────────────────────────────────────

    @Test
    fun `all twelve arabic months parse`() {
        val months = listOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        )
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        for ((ar, month) in months) {
            val parsed = ScraperText.parseArabicDate("12 $ar 2024")
            org.junit.Assert.assertNotNull(ar, parsed)
            cal.timeInMillis = parsed!!
            assertEquals(ar, 2024, cal.get(java.util.Calendar.YEAR))
            assertEquals(ar, month - 1, cal.get(java.util.Calendar.MONTH))
            assertEquals(ar, 12, cal.get(java.util.Calendar.DAY_OF_MONTH))
        }
    }

    @Test
    fun `garbage dates return null`() {
        assertNull(ScraperText.parseArabicDate(null))
        assertNull(ScraperText.parseArabicDate(""))
        assertNull(ScraperText.parseArabicDate("   "))
        assertNull(ScraperText.parseArabicDate("not a date"))
        assertNull(ScraperText.parseArabicDate("32 يناير 2024"))
    }
}
