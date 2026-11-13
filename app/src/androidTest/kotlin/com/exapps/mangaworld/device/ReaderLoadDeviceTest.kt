package com.exapps.mangaworld.device

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exapps.mangaworld.core.data.ImagePrefetcher
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.ReadingPositionSyncManager
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.reader.ReaderViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U-33 / p3-next §3: the FIRST behavioural test of the reader load path.
 *
 * `ReaderViewModel.loadChapter` has never been covered by a behavioural test:
 * the JVM suite cannot call it (AGENTS.md: the ViewModel's `beginSession()`
 * polling loop + never-completing collectors mean `advanceUntilIdle()` never
 * settles — it wedges the worker, not a flaky assertion).
 *
 * So this runs ON DEVICE (Test Lab), where a real clock and a real dispatcher
 * exist, and drives the same seam a user drives: `loadChapter(url, id, source)`.
 * The repository is a fake (the `MangaRepository` interface is the seam that
 * already exists), everything else is a relaxed mock — the point of the test is
 * the LOAD PIPELINE (state transitions), not the collaborators.
 *
 * What it pins:
 *  - a successful load populates the reader state with the chapter's pages;
 *  - the loaded chapter is the one the state reports (no cross-chapter bleed);
 *  - a repository failure surfaces as an ERROR state (not an empty reader);
 *  - a second load of the SAME chapter does not refetch (the U-16
 *    same-chapter early-return) — observable through the fake's call counter.
 */
@RunWith(AndroidJUnit4::class)
class ReaderLoadDeviceTest {

    /**
     * mockk can only intercept a FINAL class's method through its inline
     * instrumentation, and that instrumentation retransforms the class with
     * ART's JVMTI — which is not usable before API 28. On API 26/27 the call
     * therefore reaches the REAL method body (a mock's constructor is
     * bypassed, so the real body dereferences a null field and the app
     * crashes).
     *
     * Proven on the 3-device CI matrix: API 26 fails, API 30 and API 34 pass
     * the whole suite. So the test is SKIPPED (reported as an assumption
     * violation, never as a pass) on the devices where the agent cannot
     * work — the rest of the device suite still covers API 26.
     */
    @Before
    fun requireMockkInlineAgent() {
        if (Build.VERSION.SDK_INT < 28) {
            Log.w(
                "ReaderLoadDeviceTest",
                "SKIP: mockk's inline (JVMTI) agent needs ART retransform support (API 28+); " +
                    "this device is API ${Build.VERSION.SDK_INT}.",
            )
        }
        assumeTrue(
            "mockk's inline agent needs ART JVMTI retransform (API 28+)",
            Build.VERSION.SDK_INT >= 28,
        )
    }

    /** Fake repository: counts the chapter-page fetches so the test can assert the early-return. */
    private class FakeMangaRepository : MangaRepository {
        var pageCalls = 0
        var pages: List<ChapterPage> = listOf(
            ChapterPage(0, "https://example.invalid/p0.jpg"),
            ChapterPage(1, "https://example.invalid/p1.jpg"),
        )
        var failure: Throwable? = null

        override suspend fun getChapterPages(
            mangaSlug: String,
            chapterUrl: String,
            source: SourceId
        ): Result<List<ChapterPage>> {
            pageCalls++
            return failure?.let { Result.failure(it) }
                ?: Result.success(pages)
        }

        override suspend fun getHomeData(source: SourceId): Result<HomeData> = Result.success(HomeData())
        override fun searchManga(filters: com.exapps.mangaworld.domain.model.SearchFilters): Flow<androidx.paging.PagingData<MangaItem>> = emptyFlow()
        override suspend fun searchMangaDirect(query: String, source: SourceId, page: Int): Result<List<MangaItem>> = Result.success(emptyList())
        override suspend fun browseMangaDirect(source: SourceId, page: Int, genre: String?, status: com.exapps.mangaworld.domain.model.MangaStatus?, type: com.exapps.mangaworld.domain.model.MangaType?, sortBy: com.exapps.mangaworld.domain.model.SortBy): Result<List<MangaItem>> = Result.success(emptyList())
        override suspend fun getMangaByGenre(genre: String, source: SourceId, page: Int): Result<List<MangaItem>> = Result.success(emptyList())
        override suspend fun getMangaDetail(slug: String, source: SourceId): Result<MangaDetail> = Result.failure(NotImplementedError())
        override suspend fun getPopularManga(source: SourceId): Result<List<MangaItem>> = Result.success(emptyList())
        override suspend fun getSuggestedManga(candidates: List<MangaItem>, limit: Int): List<MangaItem> = emptyList()
        override suspend fun getGenres(source: SourceId?, enabledSourceIds: Set<String>?): List<String> = emptyList()
    }

    private fun viewModel(repo: MangaRepository): ReaderViewModel {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = mockk<SettingsRepository>(relaxed = true)
        // `getReaderSettings()` / `getAppSettings()` are NOT suspend (they
        // return a Flow), so they are stubbed with `every`, not `coEvery`.
        every { settings.getReaderSettings() } returns flowOf(
            com.exapps.mangaworld.domain.model.ReaderSettings()
        )
        every { settings.getAppSettings() } returns flowOf(
            com.exapps.mangaworld.domain.model.AppSettings()
        )

        // The cache DAO and the download queue are stubbed EXPLICITLY rather
        // than left relaxed. A relaxed mock answers an object-returning call
        // with a child mock whose fields are null, and the reader then does
        // `cacheDao.get(mangaId)?.toDetail(source)` — `MangaDetail.id` is a
        // non-null String, so the child mock crashed the app with an NPE
        // ("Parameter specified as non-null is null: parameter id"). The
        // cache miss and the empty local library are also the honest state
        // for a fresh install.
        val cacheDao = mockk<MangaCacheDao>(relaxed = true)
        coEvery { cacheDao.get(any()) } returns null
        val downloadQueueManager = mockk<DownloadQueueManager>(relaxed = true)
        every { downloadQueueManager.getLocalChapterPages(any(), any(), any()) } returns emptyList()

        return ReaderViewModel(
            context = context,
            mangaRepo = repo,
            libraryRepo = mockk<LibraryRepository>(relaxed = true),
            settingsRepo = settings,
            communityRepository = mockk<CommunityRepository>(relaxed = true),
            downloadQueueManager = downloadQueueManager,
            cacheDao = cacheDao,
            readingStatsStore = mockk<ReadingStatsStore>(relaxed = true),
            achievementManager = mockk<com.exapps.mangaworld.core.data.AchievementManager>(relaxed = true),
            imagePrefetcher = mockk<ImagePrefetcher>(relaxed = true),
            firebaseSyncManager = mockk<FirebaseSyncManager>(relaxed = true),
            widgetShortcutCoordinator = mockk<WidgetShortcutCoordinator>(relaxed = true),
            analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true),
            remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true),
            positionSyncManager = mockk<ReadingPositionSyncManager>(relaxed = true),
            imageLoader = mockk(relaxed = true),
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
        )
    }

    @Test
    fun loadChapter_populatesTheReaderState() = runBlocking {
        val repo = FakeMangaRepository()
        val vm = viewModel(repo)

        vm.loadChapter("https://example.invalid/chapter/1", "m1", SourceId("starz"))

        val state = withTimeout(30_000L) {
            vm.state.first { !it.isLoading && (it.pages.isNotEmpty() || it.error != null) }
        }
        assertTrue("the load must not end in an error state: ${state.error}", state.error == null)
        assertEquals(2, state.pages.size)
        assertEquals("https://example.invalid/chapter/1", state.chapterUrl)
        assertEquals("m1", state.mangaId)
    }

    @Test
    fun loadChapter_sameChapterTwice_doesNotRefetch() = runBlocking {
        val repo = FakeMangaRepository()
        val vm = viewModel(repo)

        vm.loadChapter("https://example.invalid/chapter/1", "m1", SourceId("starz"))
        withTimeout(30_000L) {
            vm.state.first { !it.isLoading && it.pages.isNotEmpty() }
        }
        vm.loadChapter("https://example.invalid/chapter/1", "m1", SourceId("starz"))
        // The U-16 same-chapter early-return: the reader keeps its pages and
        // the repository is NOT called again.
        assertEquals(1, repo.pageCalls)
        assertEquals(2, vm.state.value.pages.size)
    }

    @Test
    fun loadChapter_repositoryFailure_surfacesAnError() = runBlocking {
        val repo = FakeMangaRepository().apply { failure = IllegalStateException("boom") }
        val vm = viewModel(repo)

        vm.loadChapter("https://example.invalid/chapter/1", "m1", SourceId("starz"))

        val state = withTimeout(30_000L) {
            vm.state.first { !it.isLoading && (it.error != null || it.pages.isNotEmpty()) }
        }
        assertTrue("a failed load must surface an error state", state.error != null)
        assertTrue(state.pages.isEmpty())
    }
}
