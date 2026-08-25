package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.home.HomeViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val mangaRepo = mockk<MangaRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true)
    private val analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
    private val firebaseTelemetry = mockk<FirebaseTelemetry>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val defaultSettings = AppSettings(enabledSources = setOf("azora", "olympus"))
        every { settingsRepo.getAppSettings() } returns flowOf(defaultSettings)
        every { libraryRepo.getFavorites() } returns flowOf(emptyList())
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
        firebaseTelemetry = firebaseTelemetry
    )

    @Test
    fun initialState_loadsHomeData() {
        val vm = createViewModel()
        // init block triggers loadHome via collectLatest, which completes
        // immediately with UnconfinedTestDispatcher
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.featured.size)
        assertNull(state.error)
    }

    @Test
    fun loadHome_populatesStateWithHomeData() {
        val vm = createViewModel()
        vm.loadHome(MangaSource.AZORA)
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.featured.size)
        assertEquals(1, state.latestChapters.size)
        assertEquals(1, state.trending.size)
        assertNull(state.error)
    }

    @Test
    fun loadHome_setsActiveSource() {
        val vm = createViewModel()
        vm.loadHome(MangaSource.OLYMPUS)
        assertEquals(MangaSource.OLYMPUS, vm.state.value.activeSource)
    }

    @Test
    fun loadHome_handlesError() {
        coEvery { mangaRepo.getHomeData(any()) } returns Result.failure(Exception("Network error"))
        val vm = createViewModel()
        vm.loadHome(MangaSource.AZORA)
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals("Network error", state.error)
    }

    @Test
    fun selectSource_updatesActiveSourceAndLoadsData() {
        val vm = createViewModel()
        vm.selectSource(MangaSource.OLYMPUS)
        assertEquals(MangaSource.OLYMPUS, vm.state.value.activeSource)
        coVerify { mangaRepo.getHomeData(MangaSource.OLYMPUS) }
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
}
