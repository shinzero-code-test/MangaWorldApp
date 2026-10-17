package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.MangaRepositoryImpl
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.RecommendationEngine
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.core.source.SourceUiTestFixtures
import com.exapps.mangaworld.core.source.plugins.SourceHealthMonitor
import com.exapps.mangaworld.core.source.plugins.SourceId
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.2.5 (audit O-3): a source id with no registered scraper must produce a
 * FAILED [Result], never a thrown exception.
 *
 * Four read methods resolved the scraper outside any `runCatching`, so a source
 * that was removed, quarantined or unregistered between list render and click
 * crashed the screen instead of showing the ordinary empty/error state the UI
 * already handles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryUnavailableSourceTest {

    private fun repo(ids: Array<String> = arrayOf("hijala", "lavascans")) = MangaRepositoryImpl(
        registry = SourceUiTestFixtures.registry(*ids),
        cacheDao = mockk(relaxed = true),
        favoriteDao = mockk<FavoriteDao>(relaxed = true),
        firebaseTelemetry = mockk<FirebaseTelemetry>(relaxed = true),
        recommendationEngine = mockk<RecommendationEngine>(relaxed = true),
        health = mockk<SourceHealthMonitor>(relaxed = true)
    )

    @Test
    fun deadSourceIdFailsInsteadOfThrowing() = runTest {
        val r = repo()
        val dead = SourceId("unregistered-source")

        val search = r.searchMangaDirect("x", dead, page = 1)
        assertTrue("search must fail, not throw", search.isFailure)

        val browse = r.browseMangaDirect(dead, page = 1, genre = null, status = null, type = null,
            sortBy = com.exapps.mangaworld.domain.model.SortBy.LATEST)
        assertTrue("browse must fail, not throw", browse.isFailure)

        val genres = r.getMangaByGenre("action", dead, page = 1)
        assertTrue("genres must fail, not throw", genres.isFailure)

        val popular = r.getPopularManga(dead)
        assertTrue("popular must fail, not throw", popular.isFailure)

        val detail = r.getMangaDetail("some-slug", dead)
        assertTrue("detail must fail, not throw", detail.isFailure)

        val pages = r.getChapterPages("slug", "https://x.example/c/1", dead)
        assertTrue("pages must fail, not throw", pages.isFailure)
    }

    @Test
    fun registeredSourceIsNotReportedAsUnavailable() = runTest {
        val r = repo()
        val alive = SourceId("hijala")
        r.searchMangaDirect("x", alive, page = 1).exceptionOrNull()?.let { cause ->
            assertTrue(
                "a registered source must never fail as 'source unavailable': $cause",
                cause.message?.contains("source unavailable") != true
            )
        }
    }
}