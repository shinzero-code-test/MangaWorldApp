package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.MangaReaderBaseScraper
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Through-the-scraper MangaReader coverage (F1/F2): [parseMangaCards] is the
 * production card parser shared by HIJALA/LAVASCANS/STELLARSABER, exposed here
 * through a thin test subclass — no logic re-implemented in the test.
 */
class MangaReaderThemeFixtureTest {

    private class ExposedScraper : MangaReaderBaseScraper(
        OkHttpClient(),
        MangaSource.HIJALA,
        mockk<SettingsRepository>(relaxed = true)
    ) {
        fun cards(doc: Document): List<MangaItem> = parseMangaCards(doc)
    }

    private val scraper = ExposedScraper()

    private fun cardsDoc() = Jsoup.parse(
        javaClass.getResource("/scrapers/mangareader_cards_sample.html")!!.readText(),
        "https://hijala.com"
    )

    @Test
    fun `real parser extracts both cards`() {
        val cards = scraper.cards(cardsDoc())

        assertEquals(2, cards.size)
        assertTrue(cards.all { it.source == MangaSource.HIJALA })
    }

    @Test
    fun `query strings are stripped from slugs through real parser`() {
        val cards = scraper.cards(cardsDoc()).associateBy { it.title }

        // /manga/naruto/?ref=search must not leak "?ref=search" into the slug.
        assertEquals("naruto", cards["ناروتو"]?.slug)
        assertEquals("solo-leveling", cards["سولو ليفلينج"]?.slug)
    }

    @Test
    fun `covers resolve to absolute http urls through real parser`() {
        val cards = scraper.cards(cardsDoc())

        assertTrue(cards.all { it.coverUrl.startsWith("https://hijala.com/") })
    }
}
