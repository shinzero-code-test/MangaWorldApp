package com.exapps.mangaworld.viewmodel

/**
 * Browse/Search surface tests: BrowseViewModel, SearchViewModel,
 * SourcesViewModel, SourceBrowseViewModel.
 *
 * Notes:
 * - SourcesViewModel takes NO Android Context (ctor is settingsRepository
 *   only); relaxed mockk<Context> is used for the three VMs that require it.
 * - Paging Flows (MangaRepository.searchManga) are cold and never collected
 *   here; the direct suspend APIs (browseMangaDirect/searchMangaDirect) and
 *   synchronous uiState setters carry the assertions instead.
 * - handleFailure used to surface raw backend e.message; hardened to the
 *   generic download_error string (HomeViewModel parity) — asserted below.
 */
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.model.SortBy
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.browse.BrowseViewModel
import com.exapps.mangaworld.presentation.search.AdvancedSearchFilters
import com.exapps.mangaworld.presentation.search.SearchViewModel
import com.exapps.mangaworld.presentation.sources.SourceBrowseViewModel
import com.exapps.mangaworld.presentation.sources.SourcesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseSearchViewModelTest {

    private fun newDispatcher() = StandardTestDispatcher()
    private val mangaRepo = mockk<MangaRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        every { settingsRepo.getAppSettings() } returns
            flowOf(AppSettings(enabledSources = setOf("azora", "olympus")))
        every { settingsRepo.isSourceNotificationEnabled(any()) } returns flowOf(true)
        coEvery { mangaRepo.getGenres(any(), any()) } returns listOf("Action", "Drama")
        coEvery {
            mangaRepo.browseMangaDirect(any(), any(), any(), any(), any(), any())
        } returns Result.success(listOf(testManga("b1")))
        coEvery { mangaRepo.searchMangaDirect(any(), any(), any()) } returns
            Result.success(listOf(testManga("s1")))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun browseVm() = BrowseViewModel(
        context = context,
        repo = mangaRepo,
        settingsRepo = settingsRepo
    )

    private fun searchVm() = SearchViewModel(
        repo = mangaRepo,
        settingsRepo = settingsRepo,
        analyticsManager = analyticsManager,
        context = context,
        savedStateHandle = SavedStateHandle()
    )

    private fun sourcesVm() = SourcesViewModel(settingsRepository = settingsRepo)

    private fun sourceBrowseVm(sourceId: String = "azora") = SourceBrowseViewModel(
        context = context,
        mangaRepository = mangaRepo,
        settingsRepository = settingsRepo,
        savedStateHandle = SavedStateHandle(mapOf("sourceId" to sourceId))
    )

    // ─── BrowseViewModel ────────────────────────────────────────────────────

    @Test
    fun browse_initialStateHasDefaults() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = browseVm()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertEquals("", s.query)
            assertNull(s.selectedSource)
            assertNull(s.selectedGenre)
            assertNull(s.selectedStatus)
            assertNull(s.selectedType)
            assertEquals(SortBy.LATEST, s.sortBy)
            assertTrue(s.isGridView)
        }
    }

    @Test
    fun browse_settersUpdateUiStateAndFilters() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = browseVm()
            advanceUntilIdle()
            vm.setQuery("one piece")
            vm.setGenre("Action")
            vm.setSource(MangaSource.OLYMPUS)
            vm.setStatus(MangaStatus.COMPLETED)
            vm.setType(MangaType.MANHWA)
            vm.setSortBy(SortBy.POPULARITY)
            vm.toggleView()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertEquals("one piece", s.query)
            assertEquals("Action", s.selectedGenre)
            assertEquals(MangaSource.OLYMPUS, s.selectedSource)
            assertEquals(MangaStatus.COMPLETED, s.selectedStatus)
            assertEquals(MangaType.MANHWA, s.selectedType)
            assertEquals(SortBy.POPULARITY, s.sortBy)
            assertFalse(s.isGridView)
            // Derived SearchFilters mirror the UI selections.
            val f = s.filters
            assertEquals("one piece", f.query)
            assertEquals("Action", f.genre)
            assertEquals(MangaSource.OLYMPUS, f.source)
        }
    }

    @Test
    fun browse_loadsGenresAndSyncsEnabledSources() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = browseVm()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertTrue(s.genres.contains("Action"))
            assertEquals(setOf("azora", "olympus"), s.enabledSourceIds)
        }
    }

    // ─── SearchViewModel ────────────────────────────────────────────────────

    @Test
    fun search_initialStateHasDefaults() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = searchVm()
            advanceUntilIdle()
            assertEquals("", vm.query.value)
            assertNull(vm.source.value)
            assertEquals(AdvancedSearchFilters(), vm.filters.value)
            assertFalse(vm.showAdvancedFilters.value)
            assertTrue(vm.searchHistory.value.isEmpty())
        }
    }

    @Test
    fun search_setQueryUpdatesQueryAndFilters() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = searchVm()
            advanceUntilIdle()
            vm.setQuery("bleach")
            advanceUntilIdle()
            assertEquals("bleach", vm.query.value)
            assertEquals("bleach", vm.filters.value.query)
            vm.clear()
            advanceUntilIdle()
            assertEquals("", vm.query.value)
        }
    }

    @Test
    fun search_sourceSelectionGenreFilteringAndCloudflare() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            // Mutable settings so the test can disable a source mid-test: the
            // VM clears the selection only when enabledSources re-emits, not
            // when setSource is called (verified below).
            val settingsFlow = MutableStateFlow(
                AppSettings(enabledSources = setOf("azora", "olympus", "starz"))
            )
            every { settingsRepo.getAppSettings() } returns settingsFlow
            val vm = searchVm()
            advanceUntilIdle()
            // Enabled source sticks + verification-gated banner shows.
            vm.setSource(MangaSource.OLYMPUS)
            assertEquals(MangaSource.OLYMPUS, vm.source.value)
            assertTrue(vm.shouldShowCloudflareBanner())
            // Non-gated source: no banner.
            vm.setSource(MangaSource.AZORA)
            assertFalse(vm.shouldShowCloudflareBanner())
            // Selecting a source does NOT reset it by itself…
            vm.setSource(MangaSource.STARZ)
            advanceUntilIdle()
            assertEquals(MangaSource.STARZ, vm.source.value)
            // …but disabling it upstream does.
            settingsFlow.value = AppSettings(enabledSources = setOf("azora"))
            advanceUntilIdle()
            assertNull(vm.source.value)
            // Genre filtering via advanced filters.
            vm.setAdvancedFilter(genre = "Action", status = MangaStatus.COMPLETED)
            assertEquals("Action", vm.filters.value.genre)
            assertEquals(MangaStatus.COMPLETED, vm.filters.value.status)
            vm.clearAdvancedFilters()
            assertNull(vm.filters.value.genre)
            // Cloudflare cause classifier.
            assertTrue(
                SearchViewModel.isCloudflareCause(
                    CloudflareChallengeException("example.com", "https://example.com")
                )
            )
            assertFalse(SearchViewModel.isCloudflareCause(RuntimeException("boom")))
        }
    }

    @Test
    fun search_historyAddMoveRemoveClear() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = searchVm()
            advanceUntilIdle()
            vm.addToHistory("naruto")
            vm.addToHistory("bleach")
            assertEquals(listOf("bleach", "naruto"), vm.searchHistory.value)
            // Re-adding moves the entry to the top instead of duplicating.
            vm.addToHistory("naruto")
            assertEquals(listOf("naruto", "bleach"), vm.searchHistory.value)
            vm.removeFromHistory("bleach")
            assertEquals(listOf("naruto"), vm.searchHistory.value)
            vm.clearHistory()
            assertTrue(vm.searchHistory.value.isEmpty())
        }
    }

    // ─── SourcesViewModel ───────────────────────────────────────────────────

    @Test
    fun sources_initialStateReflectsSettings() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = sourcesVm()
            advanceUntilIdle()
            val s = vm.state.value
            assertTrue(s.enabledSources["azora"] == true)
            assertTrue(s.enabledSources["starz"] == false)
            assertTrue(s.notificationStates["azora"] == true)
        }
    }

    @Test
    fun sources_toggleSourceUpdatesStateAndRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = sourcesVm()
            advanceUntilIdle()
            vm.toggleSource("azora", false)
            advanceUntilIdle()
            assertTrue(vm.state.value.enabledSources["azora"] == false)
            coVerify { settingsRepo.toggleSource("azora", false) }
        }
    }

    // ─── SourceBrowseViewModel ──────────────────────────────────────────────

    @Test
    fun sourceBrowse_successPopulatesItemsAndEmptyEndsPaging() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = sourceBrowseVm()
            advanceUntilIdle()
            var s = vm.uiState.value
            assertEquals(MangaSource.AZORA, s.source)
            assertFalse(s.isLoading)
            assertNull(s.errorText)
            assertEquals(listOf("b1"), s.mangaList.map { it.id })
            assertTrue(s.hasMore)
            // Empty query result: list cleared, no further pages.
            coEvery { mangaRepo.searchMangaDirect(any(), any(), any()) } returns
                Result.success(emptyList())
            vm.setQuery("zzz-no-match")
            advanceUntilIdle()
            s = vm.uiState.value
            assertFalse(s.isLoading)
            assertTrue(s.mangaList.isEmpty())
            assertFalse(s.hasMore)
        }
    }

    @Test
    fun sourceBrowse_failureSetsErrorAndCloudflareSetsFlag() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery {
                mangaRepo.browseMangaDirect(any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("boom-backend-xyz"))
            val vm = sourceBrowseVm()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertFalse(s.isLoading)
            assertNotNull(s.errorText)
            // Generic string: raw backend text must never reach UI state.
            assertNotEquals("boom-backend-xyz", s.errorText)
            assertFalse(s.needsCloudflare)
            // Cloudflare challenge takes the solver path, not the error path.
            coEvery {
                mangaRepo.browseMangaDirect(any(), any(), any(), any(), any(), any())
            } returns Result.failure(
                CloudflareChallengeException("example.com", "https://example.com")
            )
            val cfVm = sourceBrowseVm()
            advanceUntilIdle()
            assertTrue(cfVm.uiState.value.needsCloudflare)
            assertFalse(cfVm.uiState.value.isLoading)
            assertNull(cfVm.uiState.value.errorText)
        }
    }

    @Test
    fun sourceBrowse_loadMoreAppendsNextPage() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery {
                mangaRepo.browseMangaDirect(any(), any(), any(), any(), any(), any())
            } answers { Result.success(listOf(testManga("p${secondArg<Int>()}"))) }
            val vm = sourceBrowseVm()
            advanceUntilIdle()
            assertEquals(listOf("p1"), vm.uiState.value.mangaList.map { it.id })
            vm.loadMore()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertEquals(2, s.currentPage)
            assertEquals(listOf("p1", "p2"), s.mangaList.map { it.id })
            assertFalse(s.isLoading)
            assertTrue(s.hasMore)
        }
    }

    private fun testManga(id: String) = MangaItem(
        id = id, slug = "slug-$id", title = "Manga $id",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA
    )
}
