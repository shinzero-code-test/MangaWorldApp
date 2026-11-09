package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.ScraperText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U-25: parser emits, reader consumes — one canonical number per chapter.
 * Round-trip: detail parser (data-num/label/URL) vs reader fallback must agree.
 */
class CanonicalChapterNumberTest {

    @Test
    fun parserLabelAndReaderUrlAgree() {
        // Detail parser: first number in label (MangaReader/Madara rule).
        val parserNumber = ScraperText.firstChapterNumber("الفصل 12 : 3 وحوش")!!
        // Reader fallback on the SAME chapter's URL: last segment number.
        val readerNumber = ScraperText.lastSegmentNumber("https://x.com/manga/abc/12")!!
        assertEquals(parserNumber, readerNumber)
    }

    @Test
    fun canonicalPrefersLabelThenUrl() {
        assertEquals(12.5f, ScraperText.canonicalChapterNumber("Chapter 12.5", "https://x.com/abc/99"))
        assertEquals(7f, ScraperText.canonicalChapterNumber(null, "https://x.com/abc/7"))
        assertNull(ScraperText.canonicalChapterNumber("", "https://x.com/abc/none"))
        assertNull(ScraperText.canonicalChapterNumber(null, null))
    }

    @Test
    fun disagreeingUrlUsesLabelRuleNotStrippedDigits() {
        // Old reader fallback stripped non-digits: "chapter-12-special" → 12 (accidental).
        // Canonical uses strict last-segment parse; label wins when present.
        assertEquals(12f, ScraperText.canonicalChapterNumber("Chapter 12", "https://x.com/abc/chapter-12-special"))
    }
}
