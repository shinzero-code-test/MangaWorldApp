package com.exapps.mangaworld.viewmodel

// Coverage notes (closest-pure-helper substitutions):
// - LibraryViewModel has no error field (init collectors just update lists; sync
//   failures are swallowed by runCatching), so the "generic error message" rule does
//   not apply here; isLoading is never set true either, asserted as false.
// - ReadingStatsViewModel exposes no actions or tab/filter methods — only the combined
//   store flows plus pure derived vals on ReadingStatsUiState (formattedReadingTime,
//   averages, recentDailyStats). Grouping/filtering is tested via the pure state.
// - DownloadsViewModel exposes only the tasks flow + action forwarding. Status grouping,
//   per-status filter chips, and unknown-status fallback strings all live in the
//   DownloadsScreen composables (MangaGroupHeader/ChapterDownloadCard), not in the VM,
//   so they cannot be covered by a JVM unit test.
// - All constructor deps were mockable with relaxed mockk; the only Android-framework
//   type needed is Context (LibraryViewModel), passed as a relaxed mock.
// - ReadingStatsViewModel.state and DownloadsViewModel.tasks use
//   SharingStarted.WhileSubscribed, so flow-population tests must collect (Turbine);
//   plain .value reads without subscribers only see the initial value.

import android.content.Context
import com.exapps.mangaworld.core.data.DailyStat
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.firebase.FirebaseTopicManager
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.FavoriteManga
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.ReadingHistoryItem
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.presentation.downloads.DownloadsViewModel
import com.exapps.mangaworld.presentation.library.LibraryTab
import com.exapps.mangaworld.presentation.library.LibraryViewModel
import com.exapps.mangaworld.presentation.stats.ReadingStatsUiState
import com.exapps.mangaworld.presentation.stats.ReadingStatsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LibraryDownloadsViewModelTest {
    // Fresh dispatcher per test: init collectors from a previous VM stay parked
    // on their abandoned scheduler (never advanced) instead of leaking into
    // the next test. ViewModel.clear() is internal, so this is the cleanup.
    private fun newDispatcher() = StandardTestDispatcher()

    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val syncManager = mockk<FirebaseSyncManager>(relaxed = true)
    private val topicManager = mockk<FirebaseTopicManager>(relaxed = true)
    private val widgetCoordinator = mockk<WidgetShortcutCoordinator>(relaxed = true)
    private val statsStore = mockk<ReadingStatsStore>(relaxed = true)
    private val downloadManager = mockk<DownloadQueueManager>(relaxed = true)

    @Before
    fun setup() {
        // Shared stubs only; per-test overrides happen before VM creation.
        every { libraryRepo.getFavorites() } returns flowOf(emptyList())
        every { libraryRepo.getReadingHistory() } returns flowOf(emptyList())
        every { statsStore.totalReadingTimeMs } returns flowOf(0L)
        every { statsStore.currentStreak } returns flowOf(0)
        every { statsStore.longestStreak } returns flowOf(0)
        every { statsStore.totalMangaRead } returns flowOf(0)
        every { statsStore.dailyStats } returns flowOf(emptyList())
        every { downloadManager.observeTasks() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createLibraryVm() = LibraryViewModel(
        context = mockk<Context>(relaxed = true),
        repo = libraryRepo,
        firebaseSyncManager = syncManager,
        firebaseTopicManager = topicManager,
        widgetShortcutCoordinator = widgetCoordinator
    )

    // ─── LibraryViewModel ────────────────────────────────────────────────────

    @Test
    fun library_initialState_defaultsToFavoritesTabAndEmpty() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createLibraryVm()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(LibraryTab.FAVORITES, state.activeTab)
        assertTrue(state.favorites.isEmpty())
        assertTrue(state.history.isEmpty())
        assertFalse(state.isLoading)
        }
    }

    @Test
    fun library_favoritesAndHistoryPopulateFromRepoFlows() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { libraryRepo.getFavorites() } returns flowOf(
            listOf(testFavorite("f1"), testFavorite("f2"))
        )
        every { libraryRepo.getReadingHistory() } returns flowOf(
            listOf(testHistory("h1"))
        )
        val vm = createLibraryVm()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(listOf("f1", "f2"), state.favorites.map { it.mangaId })
        assertEquals(listOf("h1"), state.history.map { it.mangaId })
        assertFalse(state.isLoading)
        }
    }

    @Test
    fun library_selectTab_updatesActiveTab() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createLibraryVm()
        advanceUntilIdle()
        vm.selectTab(LibraryTab.HISTORY)
        assertEquals(LibraryTab.HISTORY, vm.state.value.activeTab)
        vm.selectTab(LibraryTab.FAVORITES)
        assertEquals(LibraryTab.FAVORITES, vm.state.value.activeTab)
        }
    }

    @Test
    fun library_removeFavorite_forwardsToRepoAndSyncs() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createLibraryVm()
        advanceUntilIdle()
        vm.removeFavorite("f1")
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryRepo.removeFavorite("f1") }
        coVerify(exactly = 1) { topicManager.unsubscribeFromManga("f1") }
        coVerify(atLeast = 1) { syncManager.pushLocalSnapshot() }
        coVerify(atLeast = 1) { widgetCoordinator.refreshWidgets() }
        }
    }

    @Test
    fun library_historyMutations_forwardToRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = createLibraryVm()
        advanceUntilIdle()
        vm.removeHistory("h1")
        vm.clearHistory()
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryRepo.removeFromHistory("h1") }
        coVerify(exactly = 1) { libraryRepo.clearHistory() }
        coVerify(atLeast = 2) { syncManager.pushLocalSnapshot() }
        coVerify(atLeast = 2) { widgetCoordinator.refreshWidgetsAndShortcuts() }
        }
    }

    // ─── ReadingStatsViewModel ───────────────────────────────────────────────

    @Test
    fun stats_initialState_isLoadingBeforeCollection() {
        // WhileSubscribed: without an active collector only the initial value
        // is visible, which must be the loading state with zeroed counters.
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = ReadingStatsViewModel(statsStore)
        val state = vm.state.value
        assertTrue(state.isLoading)
        assertEquals(0L, state.totalReadingTimeMs)
        assertEquals(0, state.currentStreak)
        assertTrue(state.dailyStats.isEmpty())
        }
    }

    @Test
    fun stats_populateFromStoreFlows() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { statsStore.totalReadingTimeMs } returns flowOf(7_200_000L)
        every { statsStore.currentStreak } returns flowOf(3)
        every { statsStore.longestStreak } returns flowOf(10)
        every { statsStore.totalMangaRead } returns flowOf(5)
        every { statsStore.dailyStats } returns flowOf(
            listOf(DailyStat(date = "2026-09-01", pagesRead = 20, readingTimeMs = 600_000L))
        )
        val vm = ReadingStatsViewModel(statsStore)
        vm.state.test {
            awaitItem() // initial loading state
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.isLoading)
            assertEquals(7_200_000L, state.totalReadingTimeMs)
            assertEquals(3, state.currentStreak)
            assertEquals(10, state.longestStreak)
            assertEquals(5, state.totalMangaRead)
            assertEquals(1, state.dailyStats.size)
            assertEquals("2h 0m", state.formattedReadingTime)
            cancelAndIgnoreRemainingEvents()
        }
        }
    }

    @Test
    fun stats_derivedHelpers_computeDisplayValues() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val today = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val state = ReadingStatsUiState(
            totalReadingTimeMs = 3_700_000L,
            dailyStats = listOf(
                DailyStat(date = today, pagesRead = 7, readingTimeMs = 60_000L),
                DailyStat(date = "2000-01-01", pagesRead = 100, readingTimeMs = 0L)
            )
        )
        assertEquals("1h 1m", state.formattedReadingTime)
        assertEquals(7, state.todayPages)
        // Only today's entry falls inside the trailing 7-day window.
        assertEquals(7, state.thisWeekPages)
        // 107 pages over 2 stored days.
        assertEquals(53, state.averagePagesPerDay)
        // Reverse chronological order.
        assertEquals(listOf(today, "2000-01-01"), state.recentDailyStats.map { it.date })
        assertEquals(0, ReadingStatsUiState().averagePagesPerDay)
        val many = (1..16).map {
            DailyStat(date = "2026-08-%02d".format(it), pagesRead = it, readingTimeMs = 0L)
        }
        val recent = ReadingStatsUiState(dailyStats = many).recentDailyStats
        assertEquals(14, recent.size)
        assertEquals("2026-08-16", recent.first().date)
        }
    }

    // ─── DownloadsViewModel ──────────────────────────────────────────────────

    @Test
    fun downloads_initialTasksEmpty() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = DownloadsViewModel(downloadManager)
        advanceUntilIdle()
        assertTrue(vm.tasks.value.isEmpty())
        }
    }

    @Test
    fun downloads_tasksPopulateFromManagerFlow() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { downloadManager.observeTasks() } returns flowOf(
            listOf(testTask("t1", "running"), testTask("t2", "completed"))
        )
        val vm = DownloadsViewModel(downloadManager)
        vm.tasks.test {
            awaitItem() // initial empty list
            advanceUntilIdle()
            val tasks = expectMostRecentItem()
            assertEquals(2, tasks.size)
            assertEquals("t1", tasks[0].id)
            assertEquals("completed", tasks[1].status)
            cancelAndIgnoreRemainingEvents()
        }
        }
    }

    @Test
    fun downloads_cancelAndRetryForwardToManager() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = DownloadsViewModel(downloadManager)
        advanceUntilIdle()
        vm.cancelTask("t1")
        vm.retryTask("t2")
        advanceUntilIdle()
        coVerify(exactly = 1) { downloadManager.cancelTask("t1") }
        coVerify(exactly = 1) { downloadManager.retryTask("t2") }
        }
    }

    @Test
    fun downloads_pauseResumeAndBulkActionsForwardToManager() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = DownloadsViewModel(downloadManager)
        advanceUntilIdle()
        vm.pauseTask("t1")
        vm.resumeTask("t1")
        vm.pauseAll()
        vm.resumeAll()
        vm.clearCompleted()
        vm.cancelAll()
        vm.cancelMangaDownloads("m1")
        advanceUntilIdle()
        coVerify(exactly = 1) { downloadManager.pauseTask("t1") }
        coVerify(exactly = 1) { downloadManager.resumeTask("t1") }
        coVerify(exactly = 1) { downloadManager.pauseAll() }
        coVerify(exactly = 1) { downloadManager.resumeAll() }
        coVerify(exactly = 1) { downloadManager.clearCompleted() }
        coVerify(exactly = 1) { downloadManager.cancelAllDownloads() }
        coVerify(exactly = 1) { downloadManager.cancelMangaDownloads("m1") }
        }
    }

    private fun testFavorite(id: String) = FavoriteManga(
        mangaId = id, slug = "slug-$id", title = "Title $id",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA
    )

    private fun testHistory(id: String) = ReadingHistoryItem(
        mangaId = id, slug = "slug-$id", title = "Title $id",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA,
        lastChapterNumber = 5f,
        lastReadAt = 123L
    )

    private fun testTask(id: String, status: String) = DownloadTaskEntity(
        id = id, mangaId = "m1", mangaTitle = "Manga",
        chapterUrl = "https://example.com/ch/$id",
        chapterTitle = "Chapter $id",
        targetDir = "/tmp",
        status = status
    )
}
