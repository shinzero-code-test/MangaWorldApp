package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperFixtureTest {

    // ─── Chapter Selector Tests ───────────────────────────────────────────────

    @Test
    fun olympusChapterSelectorFixture() {
        val html = javaClass.getResource("/scrapers/olympus_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val cards = doc.select(".chapter-card[data-number]")
        assertEquals(1, cards.size)
        assertEquals("12", cards.first()!!.attr("data-number"))
        assertEquals("500", cards.first()!!.attr("data-views"))
        assertEquals("1700000000", cards.first()!!.attr("data-date"))
    }

    @Test
    fun azoraChapterSelectorFixture() {
        val html = javaClass.getResource("/scrapers/azora_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val rows = doc.select("div.mt-4.space-y-2 > div")
        assertEquals(1, rows.size)
        assertTrue(rows.first()!!.selectFirst("a[href*='chapter']") != null)
    }

    @Test
    fun starzChapterSelectorFixture() {
        val html = javaClass.getResource("/scrapers/starz_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        assertEquals(1, doc.select(".listing-chapters_wrap li").size)
        assertEquals(1, doc.select("select.single-chapter-select option[data-redirect]").size)
    }

    // ─── Genre Selector Tests ─────────────────────────────────────────────────

    @Test
    fun azoraGenreSelectorFixture() {
        val html = javaClass.getResource("/scrapers/azora_genre_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val genres = doc.select("a[href*=\"/series?genres=\"]")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
        assertEquals(3, genres.size)
        assertTrue(genres.contains("أكشن"))
    }

    @Test
    fun olympusGenreSelectorFixture() {
        val html = javaClass.getResource("/scrapers/olympus_genre_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val genres = doc.select("a.subtitle[href*=\"genre\"]")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
        assertEquals(3, genres.size)
        assertTrue(genres.contains("كوميدي"))
    }

    @Test
    fun olympusSearchPageFixtureUsesCurrentListupdCards() {
        val html = javaClass.getResource("/scrapers/olympus_search_page_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val cards = doc.select(".listupd .bsx")
        assertEquals(2, cards.size)
        assertEquals("Solo Leveling", cards.first()!!.selectFirst(".tt")!!.text())
        assertEquals("مانهوا", cards.first()!!.selectFirst(".type")!!.text())
    }

    @Test
    fun olympusAjaxSearchFixtureFindsSoloResults() {
        val html = javaClass.getResource("/scrapers/olympus_ajax_search_sample.html")!!.readText()
        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val results = doc.select("a.group[href*='/series/']")
        assertEquals(2, results.size)
        assertTrue(results.any { it.attr("abs:href").endsWith("/series/solo-leveling") })
        assertTrue(results.any { it.selectFirst("h4")?.text() == "Solo Leveling: Ragnarok" })
    }

    @Test
    fun starzGenreSelectorFixture() {
        val html = javaClass.getResource("/scrapers/starz_genre_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val genres = doc.select("ul.genre-scroll-list li a")
            .map { it.text().cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
        assertEquals(3, genres.size)
        assertTrue(genres.contains("رومانسي"))
    }

    // ─── View Count Extraction Tests ──────────────────────────────────────────

    @Test
    fun azoraViewsExtractionFixture() {
        val html = javaClass.getResource("/scrapers/azora_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)
                ?.replace(",", "")
        }
        assertEquals("1500", viewsText)
    }

    @Test
    fun olympusViewsExtractionFixture() {
        val html = javaClass.getResource("/scrapers/olympus_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)
                ?.replace(",", "")
        }
        assertEquals("1200", viewsText)
    }

    @Test
    fun starzViewsExtractionFixture() {
        val html = javaClass.getResource("/scrapers/starz_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val viewsText = doc.body().text().let { text ->
            Regex("(\\d[\\d,]*)\\s*(مشاهدة|view)").find(text)?.groupValues?.get(1)
                ?.replace(",", "")
        }
        assertEquals("800", viewsText)
    }

    @Test
    fun azoraRatingExtractionFixture() {
        val html = javaClass.getResource("/scrapers/azora_detail_sample.html")!!.readText()
        val doc = Jsoup.parse(html)
        val rating = doc.selectFirst("p.inline-block")?.text()?.toFloatOrNull()
        assertEquals(4.5f, rating)
    }

    // ─── Domain Model Tests ───────────────────────────────────────────────────

    @Test
    fun mangaTypeFromArabic() {
        assertEquals(MangaType.MANGA, MangaType.from("مانجا"))
        assertEquals(MangaType.MANHWA, MangaType.from("مانهوا"))
        assertEquals(MangaType.UNKNOWN, MangaType.from("غير معروف"))
    }

    @Test
    fun mangaTypeFromEnglish() {
        assertEquals(MangaType.MANHUA, MangaType.from("manhua"))
        assertEquals(MangaType.MANHWA, MangaType.from("manhwa"))
        assertEquals(MangaType.MANGA, MangaType.from("manga"))
    }

    @Test
    fun mangaTypeFromNull() {
        assertEquals(MangaType.UNKNOWN, MangaType.from(null))
    }

    @Test
    fun mangaTypeFromMixedCase() {
        assertEquals(MangaType.MANGA, MangaType.from("ManGa"))
        assertEquals(MangaType.MANHWA, MangaType.from("MANHWA"))
    }

    @Test
    fun mangaStatusFromArabic() {
        assertEquals(MangaStatus.ONGOING, MangaStatus.from("مستمر"))
        assertEquals(MangaStatus.COMPLETED, MangaStatus.from("مكتمل"))
        assertEquals(MangaStatus.HIATUS, MangaStatus.from("متوقف"))
    }

    @Test
    fun mangaStatusFromEnglish() {
        assertEquals(MangaStatus.ONGOING, MangaStatus.from("ongoing"))
        assertEquals(MangaStatus.COMPLETED, MangaStatus.from("completed"))
        assertEquals(MangaStatus.HIATUS, MangaStatus.from("hiatus"))
    }

    @Test
    fun mangaStatusFromNull() {
        assertEquals(MangaStatus.UNKNOWN, MangaStatus.from(null))
    }

    // ─── Chapter Parsing Helpers ──────────────────────────────────────────────

    @Test
    fun starzParseChapterTitleStripsPrefix() {
        val result = "الفصل 18 عنوان القصة".replace(Regex("الفصل\\s*[0-9.,]+\\s*[:.]*\\s*"), "").trim()
        assertEquals("عنوان القصة", result)
    }

    @Test
    fun starzParseChapterTitleReturnsNullWhenOnlyNumber() {
        val result = "الفصل 18".replace(Regex("الفصل\\s*[0-9.,]+\\s*[:.]*\\s*"), "").trim()
        assertEquals("", result)
    }

    @Test
    fun starzParseChapterTitleHandlesColonSeparator() {
        val result = "الفصل 18 : عنوان".replace(Regex("الفصل\\s*[0-9.,]+\\s*[:.]*\\s*"), "").trim()
        assertEquals("عنوان", result)
    }

    @Test
    fun starzParseChapterDateFromDatetimeAttr() {
        val html = "<span datetime='2024-01-15T00:00:00Z'>منذ 3 أيام</span>"
        val el = Jsoup.parse(html).selectFirst("span")!!
        val datetime = el.attr("datetime")
        val dateLong = try {
            if (datetime.isNotBlank()) java.time.Instant.parse(datetime).toEpochMilli()
            else null
        } catch (e: Exception) { null }
        assertNotNull(dateLong)
        assertEquals(1705276800000L, dateLong)
    }
}

private fun String.cleanText() = trim().replace("\\s+".toRegex(), " ")
