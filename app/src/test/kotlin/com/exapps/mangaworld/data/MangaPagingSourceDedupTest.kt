package com.exapps.mangaworld.data

import androidx.paging.PagingSource
import com.exapps.mangaworld.core.data.MangaPagingSource
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.SearchFilters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-27(d) fetch dedup: a filtered-browse page load fetches `page..page+2`
 * per source. The next page's window overlaps the previous two subpages;
 * the memo must serve them without re-fetching (3+1 network calls, not
 * 3+3), while invalidation (refresh) must clear the memo.
 */
class MangaPagingSourceDedupTest {

    private val sourceId = "testsrc"

    private fun item(id: String) = MangaItem(
        id = id,
        slug = id,
        title = "Title $id",
        coverUrl = "",
        source = SourceId(sourceId),
        status = MangaStatus.ONGOING,
    )

    private fun pagingSource(scraper: MangaScraper): MangaPagingSource =
        MangaPagingSource(
            mapOf(sourceId to scraper),
            SearchFilters(
                status = MangaStatus.ONGOING,
                source = SourceId(sourceId),
                enabledSourceIds = setOf(sourceId),
            ),
        )

    private fun scraperReturningPageItems(): MangaScraper {
        val scraper = mockk<MangaScraper>(relaxed = true)
        coEvery { scraper.browseManga(any(), any(), any(), any(), any()) } answers {
            val p = firstArg<Int>()
            Result.success(listOf(item("p$p-a"), item("p$p-b")))
        }
        return scraper
    }

    private suspend fun loadPage(source: MangaPagingSource, key: Int) =
        source.load(PagingSource.LoadParams.Refresh(key = key, loadSize = 24, placeholdersEnabled = false))

    @Test
    fun adjacentPages_reuseOverlappingWindow() = runTest {
        val scraper = scraperReturningPageItems()
        val source = pagingSource(scraper)

        val first = loadPage(source, key = 1)
        val second = loadPage(source, key = 2)

        assertTrue(first is PagingSource.LoadResult.Page)
        assertTrue(second is PagingSource.LoadResult.Page)
        // Page 1 fetches subpages 1,2,3; page 2 only needs subpage 4.
        coVerify(exactly = 4) { scraper.browseManga(any(), any(), any(), any(), any()) }
    }

    @Test
    fun invalidation_clearsWindowMemo() = runTest {
        val scraper = scraperReturningPageItems()
        val source = pagingSource(scraper)

        loadPage(source, key = 1)
        source.invalidate()
        loadPage(source, key = 1)

        coVerify(exactly = 6) { scraper.browseManga(any(), any(), any(), any(), any()) }
    }
}
