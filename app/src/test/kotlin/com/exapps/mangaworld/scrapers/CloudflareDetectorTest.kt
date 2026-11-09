package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.core.data.remote.scraper.BaseScraperImpl
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.model.SortBy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U-22: table-driven CF detector — 403/503 alone is weak (non-CF 403 must not
 * route to solver); markers are required.
 */
class CloudflareDetectorTest {

    private class Probe : BaseScraperImpl(
        mockk<OkHttpClient>(relaxed = true),
        "probe",
        "https://example.com",
        mockk<SettingsRepository>(relaxed = true)
    ) {
        // BaseScraperImpl is abstract over the MangaScraper interface; the
        // detector checks never touch these entry points.
        override suspend fun getHomeData(): Result<HomeData> = Result.success(HomeData())
        override suspend fun getMangaDetail(slug: String): Result<MangaDetail> =
            Result.failure(NotImplementedError())
        override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> =
            Result.failure(NotImplementedError())
        override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> =
            Result.failure(NotImplementedError())
        override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> =
            Result.failure(NotImplementedError())
        override suspend fun getPopularManga(): Result<List<MangaItem>> =
            Result.failure(NotImplementedError())
        override suspend fun browseManga(
            page: Int, genre: String?, status: MangaStatus?, type: MangaType?,
            sortBy: SortBy
        ): Result<List<MangaItem>> = Result.failure(NotImplementedError())
        override suspend fun getGenres(): Result<List<String>> =
            Result.failure(NotImplementedError())

        fun isCf(code: Int, title: String, body: String, header: String? = null) =
            isCloudflareChallenge(code, title, body, header)
    }

    private val probe = Probe()

    data class Case(
        val name: String,
        val code: Int,
        val title: String,
        val body: String,
        val header: String? = null,
        val expected: Boolean
    )

    private val cases = listOf(
        Case("healthy-200", 200, "Solo Leveling", "<html><body>chapters</body></html>", expected = false),
        Case("CF-403-marker", 403, "Just a moment", "<html>…__cf_chl_…</html>", expected = true),
        Case("CF-503-header", 503, "Attention Required", "<html></html>", header = "challenge", expected = true),
        Case("CF-200-interstitial", 200, "Attention Required", "<html>…</html>", expected = true),
        Case("nonCF-403", 403, "Forbidden", "<html>IP banned</html>", expected = false),
        Case("nonCF-503", 503, "Service Unavailable", "<html>overloaded</html>", expected = false),
        Case("nonCF-404", 404, "Not Found", "<html></html>", expected = false),
        Case("CF-body-marker-only", 200, "Normal title", "…challenges.cloudflare.com/turnstile…", expected = true)
    )

    @Test
    fun detectorTable() {
        for (c in cases) {
            val actual = probe.isCf(c.code, c.title, c.body, c.header)
            assertEquals("case ${c.name}", c.expected, actual)
        }
    }
}
