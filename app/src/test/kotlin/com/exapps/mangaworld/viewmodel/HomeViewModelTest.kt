package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.home.HomeViewModel
import io.mockk.*
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
import org.junit.Assert.*
import org.junit.Before
import app.cash.turbine.test
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    // Fresh dispatcher per test: init collectors from a previous VM stay parked
    // on their abandoned scheduler (never advanced) instead of leaking into
    // the next test. ViewModel.clear() is internal, so this is the cleanup.
    private fun newDispatcher() = StandardTestDispatcher()
    private val mangaRepo = mockk<MangaRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true)
    private val analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
    private val firebaseTelemetry = mockk<FirebaseTelemetry>(relaxed = true)
    private val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val homeCacheDao = mockk<com.exapps.mangaworld.core.data.local.dao.HomeCacheDao>(relaxed = true)

    @Before
    fun setup() {
        // Main dispatcher is installed per-test (fresh StandardTestDispatcher);
        // shared stubs only here.
        val defaultSettings = AppSettings(enabledSources = setOf("azora", "olympus"))
        every { settingsRepo.getAppSettings() } returns flowOf(defaultSettings)
        every { libraryRepo.getFavorites() } returns flowOf(emptyList())
        every { sessionManager.authState } returns flowOf(null)
        every { communityRepo.observeNotifications(any()) } returns flowOf(emptyList())
        every { remoteConfigManager.remoteAlertMessage } returns MutableStateFlow("")
        every { remoteConfigManager.homeLayoutVariant } returns MutableStateFlow("default")
        coEvery { mangaRepo.getHomeData(any()) } returns Result.success(
            HomeData(
                featured = listOf(testManga("f1")),
                latestChapters = listOf(testLatest("l1")),
                trending = listOf(testManga("t1"))
            )
        )
        coEvery { mangaRepo.getSuggestedManga(any(), any()) } returns emptyList()
        // Neutral cache stub: per-test cache stubs (e.g. offline snapshot) must
        // not leak into later tests through the shared mock.
        coEvery { homeCacheDao.get(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = HomeViewModel(
        context = io.mockk.mockk(relaxed = true),
        repo = mangaRepo,
        settingsRepo = settingsRepo,
        libraryRepo = libraryRepo,
        remoteConfigManager = remoteConfigManager,
        analyticsManager = analyticsManager,
        firebaseTelemetry = firebaseTelemetry,
        sessionManager = sessionManager,
        communityRepo = communityRepo,
        homeCacheDao = homeCacheDao
    )

    @Test
    fun initialState_loadsHomeData() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.featured.size)
        assertNull(state.error)
    }
    }

    @Test
    fun loadHome_populatesStateWithHomeData() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        vm.loadHome(MangaSource.AZORA)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.featured.size)
        assertEquals(1, state.latestChapters.size)
        assertEquals(1, state.trending.size)
        assertNull(state.error)
    }
    }

    @Test
    fun loadHome_setsActiveSource() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        // Settle the init load first: racing it against the direct call below
        // leaves the winner scheduler-dependent (init's AZORA load may land
        // last, exactly like a cold start overwritten by a later tap).
        advanceUntilIdle()
        vm.loadHome(MangaSource.OLYMPUS)
        advanceUntilIdle()
        assertEquals(MangaSource.OLYMPUS, vm.state.value.activeSource)
    }
    }

    @Test
    fun loadHome_handlesError() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { mangaRepo.getHomeData(any()) } returns Result.failure(Exception("Network error"))
        val vm = createViewModel()
        vm.loadHome(MangaSource.AZORA)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.isLoading)
        // Raw backend text must not reach UI state (generic R.string.download_error
        // instead — relaxed context mock returns "" for it).
        assertNotEquals("Network error", state.error)
    }
    }

    @Test
    fun selectSource_updatesActiveSourceAndLoadsData() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.selectSource(MangaSource.OLYMPUS)
        advanceUntilIdle()
        assertEquals(MangaSource.OLYMPUS, vm.state.value.activeSource)
        coVerify { mangaRepo.getHomeData(MangaSource.OLYMPUS) }
    }
    }

    @Test
    fun loadHome_filtersBlockedKeywords() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { mangaRepo.getHomeData(any()) } returns Result.success(
            HomeData(
                featured = listOf(testManga("ok"), testManga("blocked-one").copy(title = "Blocked Manga")),
                latestChapters = listOf(testLatest("l1")),
                trending = listOf(testManga("t1"))
            )
        )
        val vm = createViewModel()
        advanceUntilIdle()
        vm.loadHome(MangaSource.AZORA, setOf("blocked"))
        advanceUntilIdle()
        val ids = vm.state.value.featured.map { it.id }
        assertEquals(listOf("ok"), ids)
    }
    }

    @Test
    fun loadHome_suggestedHasNoDuplicateIds() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        // Echo candidates back as suggestions: without the pre-scoring
        // distinctBy, the shared id would reach the LazyRow twice (crash).
        coEvery { mangaRepo.getSuggestedManga(any(), any()) } answers { firstArg() }
        val dup = testManga("dup")
        coEvery { mangaRepo.getHomeData(any()) } returns Result.success(
            HomeData(
                featured = listOf(dup),
                latestChapters = listOf(testLatest("l1")),
                trending = listOf(dup)
            )
        )
        val vm = createViewModel()
        vm.loadHome(MangaSource.AZORA)
        advanceUntilIdle()
        val suggestedIds = vm.state.value.suggested.map { it.id }
        assertEquals(suggestedIds.distinct(), suggestedIds)
        assertEquals(1, suggestedIds.size)
    }
    }

    @Test
    fun loadHome_persistsSnapshotToCache() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.loadHome(MangaSource.AZORA)
        advanceUntilIdle()
        coVerify { homeCacheDao.upsert(match { it.sourceId == "azora" && it.payloadJson.contains("f1") }) }
    }
    }

    @Test
    fun loadHome_offlineShowsCachedSnapshot() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val cached = com.exapps.mangaworld.domain.model.HomeData(
            featured = listOf(testManga("cached-1")),
            latestChapters = emptyList(),
            trending = emptyList()
        )
        coEvery { homeCacheDao.get("azora") } returns
            com.exapps.mangaworld.core.data.local.entity.HomeCacheEntity(
                sourceId = "azora",
                payloadJson = com.exapps.mangaworld.core.data.local.HomeCacheCodec.encode(cached)
            )
        coEvery { mangaRepo.getHomeData(any()) } returns Result.failure(Exception("offline"))
        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.isOffline)
        assertEquals(listOf("cached-1"), state.featured.map { it.id })
        assertNotNull(state.error)
    }
    }

    @Test
    fun init_restoresLastOpenedSource() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { settingsRepo.getAppSettings() } returns flowOf(
            AppSettings(enabledSources = setOf("azora", "olympus"), lastSourceId = "olympus")
        )
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(MangaSource.OLYMPUS, vm.state.value.activeSource)
        coVerify { settingsRepo.setLastSourceId("olympus") }
    }
    }

    private fun testManga(id: String) = MangaItem(
        id = id, slug = "slug-$id", title = "Manga $id",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA
    )

    private fun testLatest(id: String) = LatestChapterItem(
        mangaId = id, mangaSlug = "slug-$id", mangaTitle = "Manga $id",
        coverUrl = "https://example.com/cover.jpg",
        chapterNumber = 1.0f, chapterUrl = "https://example.com/ch1",
        timeAgo = "1h", source = MangaSource.AZORA
    )
    @Test
    fun selectSource_emitsLoadedState() {
        // First Turbine adoption (#17): collects across dispatcher advances
        // and asserts the latest emission after a source switch.
        // expectMostRecentItem (not an ordered awaitItem chain) because
        // StateFlow conflation may swallow the transient loading pulse.
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createViewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        vm.state.test {
            awaitItem() // current init-loaded state
            vm.selectSource(MangaSource.OLYMPUS)
            advanceUntilIdle()
            val final = expectMostRecentItem()
            assertEquals(MangaSource.OLYMPUS, final.activeSource)
            assertFalse(final.isLoading)
            assertEquals(1, final.featured.size)
            assertNull(final.error)
            cancelAndIgnoreRemainingEvents()
        }
        }
    }
}
