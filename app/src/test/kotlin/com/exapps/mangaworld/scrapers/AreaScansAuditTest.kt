package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.AreaScansScraper
import com.exapps.mangaworld.core.data.remote.scraper.isSecureChapterLocked
import io.mockk.mockk
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit-driven fixtures for AREA Manga (report 13): `.info-row` metadata contract,
 * colliding `data-ch` chapter rows (both must survive), and the locked-branch
 * predicate for the secure-chapter AJAX payload.
 */
class AreaScansAuditTest {

    private fun scraper(): AreaScansScraper = AreaScansScraper(
        client = mockk(relaxed = true),
        settingsRepo = mockk(relaxed = true),
        context = mockk(relaxed = true)
    )

    private fun fixtureDoc() = Jsoup.parse(
        javaClass.getResource("/scrapers/areascans_detail_sample.html")!!.readText(),
        "https://ar.kenmanga.com"
    )

    @Test
    fun infoRowsParsed() {
        val info = scraper().parseInfoRows(fixtureDoc())
        assertEquals("Unknown", info["Author"])
        assertEquals("Ongoing", info["Status"])
        assertEquals("8,001", info["Views"])
    }

    @Test
    fun collidingChapterNumbersBothPreserved() {
        val rows = fixtureDoc().select("#chapters-list-container .chapter-item.ch-item")
        assertEquals(2, rows.size)
        // Same data-ch, distinct data-id AND distinct URLs — a distinctBy{number}
        // would silently drop one part.
        assertEquals(listOf("272043", "272044"), rows.map { it.attr("data-id") })
        assertEquals(2, rows.map { it.selectFirst("a[href]")!!.attr("abs:href") }.toSet().size)
    }

    @Test
    fun lockedBranchDetected() {
        assertTrue(isSecureChapterLocked(JSONObject("""{"status":"locked","shortlink":"https://x/y"}""")))
        assertFalse(isSecureChapterLocked(JSONObject("""{"status":"unlocked","content":"<img>"}""")))
        assertFalse(isSecureChapterLocked(JSONObject("""{}""")))
    }
}
