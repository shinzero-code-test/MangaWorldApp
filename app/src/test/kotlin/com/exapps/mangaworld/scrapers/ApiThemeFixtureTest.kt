package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.AzoraScraper
import com.exapps.mangaworld.core.data.remote.scraper.MeshmangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.ProComicScraper
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Through-the-scraper API-theme coverage (#4): every assertion drives the REAL
 * Azora/Meshmanga/ProComic JSON parsers. The Astro wire format and the
 * appswat/procomic JSON shapes are the contract with the server — a drift
 * that breaks 3 API-theme sources must fail here, not in production.
 */
class ApiThemeFixtureTest {

    private fun azora() = AzoraScraper(
        OkHttpClient(),
        mockk<SettingsRepository>(relaxed = true)
    )

    private fun meshmanga() =
        MeshmangaScraper(
            OkHttpClient(),
            mockk<SettingsRepository>(relaxed = true)
        )

    private fun procomic() =
        ProComicScraper(
            OkHttpClient(),
            mockk<SettingsRepository>(relaxed = true)
        )

    // ── Azora wire format ───────────────────────────────────────────────────

    @Test
    fun `wire scalar decodes through real parser`() {
        val s = azora()
        assertEquals("hello", s.decodeStr(JSONArray("[0,\"hello\"]")))
        assertEquals(42, s.decodeInt(JSONArray("[0,42]")))
        assertEquals(true, s.decodeBool(JSONArray("[0,true]")))
        assertNull(s.decodeLong(JSONArray("[0,null]")))
    }

    @Test
    fun `wire list decodes through real parser`() {
        val s = azora()
        val decoded = s.decodeList(JSONArray("[1,[[0,\"a\"],[0,\"b\"]]]"))
        assertEquals(listOf("a", "b"), decoded)
    }

    @Test
    fun `wire nested object decodes through real parser`() {
        val s = azora()
        val decoded = s.decodeWire(JSONObject("{\"k\":[0,\"v\"],\"n\":[0,3]}")) as Map<*, *>
        assertEquals("v", decoded["k"])
        assertEquals(3, decoded["n"])
    }

    @Test
    fun `island extraction finds the named component through real parser`() {
        val s = azora()
        val html = "<html><body>" +
            "<astro-island opts='{\"name\":\"Other\"}' props=\"{}\"></astro-island>" +
            "<astro-island opts=\"{&quot;name&quot;:&quot;SeriesChaptersPanelIsland&quot;}\" " +
            "props=\"{&quot;post&quot;:[0,&quot;one-piece&quot;]}\"></astro-island>" +
            "</body></html>"
        val props = s.extractIslandProps(html, "SeriesChaptersPanelIsland")
        assertNotNull(props)
        assertEquals("one-piece", s.decodeStr(props!!.get("post")))
    }

    @Test
    fun `island extraction returns null when component absent`() {
        val s = azora()
        assertNull(s.extractIslandProps("<html><body>no islands</body></html>", "SeriesChaptersPanelIsland"))
        assertNull(s.extractIslandProps("", "SeriesChaptersPanelIsland"))
    }

    @Test
    fun `tag scanner respects quoted attributes`() {
        val s = azora()
        val tag = "<astro-island opts=\"a>b\" props=\"{}\">"
        // The '>' inside quotes must not terminate the tag.
        assertTrue(s.findOpenTagEnd(tag, 0) > tag.indexOf("a>b"))
        assertEquals("a>b", s.extractAttrValue(tag, "opts"))
        assertNull(s.extractAttrValue(tag, "missing"))
    }

    // ── Meshmanga JSON mappers ──────────────────────────────────────────────

    @Test
    fun `series mapper rejects invalid rows through real parser`() {
        val m = meshmanga()
        assertNull(m.seriesItemFrom(null))
        assertNull(m.seriesItemFrom(JSONObject("{}")))
        assertNull(m.seriesItemFrom(JSONObject("{\"id\":5,\"title\":\"\"}")))
    }

    @Test
    fun `series mapper builds stable ids through real parser`() {
        val m = meshmanga()
        val item = m.seriesItemFrom(
            JSONObject(
                "{\"id\":7,\"title\":\"  Solo  \",\"poster\":{\"medium\":\"https://cdn/x.jpg\"}," +
                    "\"status\":{\"name\":\"مستمر\"},\"genres\":[{\"name\":\"اكشن\"}],\"chapters_count\":12}"
            )
        )
        assertNotNull(item)
        assertEquals("meshmanga_7", item!!.id)
        assertEquals("7", item.slug)
        assertEquals("Solo", item.title)
        assertEquals(listOf("اكشن"), item.genres)
        assertEquals(12, item.totalChapters)
        assertEquals(com.exapps.mangaworld.domain.model.MangaStatus.ONGOING, item.status)
    }

    @Test
    fun `chapter number parser tolerates prefixes through real parser`() {
        val m = meshmanga()
        assertEquals(12f, m.parseChapterNumber("الفصل 12"))
        assertEquals(11.5f, m.parseChapterNumber("chapter-11.5"))
        assertNull(m.parseChapterNumber("قريباً"))
    }

    @Test
    fun `chapter url keeps integer display form through real parser`() {
        val m = meshmanga()
        assertTrue(m.buildChapterUrl("9", 100L, 12f).endsWith("/chapter-12"))
        assertTrue(m.buildChapterUrl("9", 101L, 11.5f).endsWith("/chapter-11.5"))
    }

    // ── ProComic JSON mapper ────────────────────────────────────────────────

    @Test
    fun `procomic mapper skips blank slugs through real parser`() {
        val p = procomic()
        val json = JSONObject(
            "{\"data\":[{\"slug\":\"\",\"title\":\"x\"}," +
                "{\"slug\":\"naruto\",\"title\":\" ناروتو \",\"thumbnail\":\"https://cdn/n.jpg\"," +
                "\"status\":\"مكتمل\",\"metadata\":{\"genres\":[\"اكشن\"],\"type\":\"manga\"},\"id\":3,\"type\":\"manga\"}]}"
        )
        val items = p.parseApiResults(json)
        assertEquals(1, items.size)
        assertEquals("procomic_naruto", items[0].id)
        assertEquals("ناروتو", items[0].title)
        assertEquals(com.exapps.mangaworld.domain.model.MangaStatus.COMPLETED, items[0].status)
    }

    @Test
    fun `procomic mapper handles empty payloads`() {
        val p = procomic()
        assertTrue(p.parseApiResults(null).isEmpty())
        assertTrue(p.parseApiResults(JSONObject("{}")).isEmpty())
    }
}
