package com.exapps.mangaworld.viewmodel

import android.content.Context
import com.exapps.mangaworld.core.data.AchievementManager
import com.exapps.mangaworld.core.data.ImagePrefetcher
import com.exapps.mangaworld.core.data.ReadingPositionSyncManager
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.local.entity.DownloadTaskEntity
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.core.firebase.FirebaseTopicManager
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.Chapter
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.FavoriteManga
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.ReaderMode
import com.exapps.mangaworld.domain.model.ReaderSettings
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.detail.MangaDetailViewModel
import com.exapps.mangaworld.presentation.reader.ReaderViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers [MangaDetailViewModel] deeply plus the publicly observable surface of
 * [ReaderViewModel] (state defaults, load success/failure, saved-progress
 * restore, tap routing, neighbour-navigation edges, typed download-failure
 * signal).
 *
 * Coverage gaps (deliberate — private or platform-bound surface):
 * - Reader privates: neighborChapter/ensureNextChapter chaining,
 *   toDownloadMessage/toDownloadCodeMessage (asserted only indirectly through
 *   [ReaderViewModel.state]), parseFallbackChapterNumber (indirect),
 *   prefetchNextChapterIfNeeded, scheduleAutoCleanupIfNeeded (WorkManager
 *   static — avoided by keeping autoCleanupReadDownloads=false),
 *   trackReadingTime/beginSession timing, saveCurrentPage (coil + MediaStore).
 * - Detail privates: hasLocalData/loadFromLocalDisk local-disk path (real
 *   filesystem), loadOtherSourceMatches ranking beyond the empty-stub,
 *   source-comparison network fan-out, Cloudflare WebView round-trip.
 * - ReaderViewModel has no unmockable constructor deps (concrete collaborators
 *   are all mockk-relaxed, including final coil.ImageLoader); tests avoid the
 *   static WorkManager entry point only.
 *
 * Both VMs hop to Dispatchers.IO internally (withContext(IO) around mocked
 * DAO/queue calls), which the test scheduler cannot advance — [TestScope.awaitUntil]
 * polls with real short sleeps for those paths instead of relying on
 * advanceUntilIdle() alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailReaderViewModelTest {

    private fun newDispatcher() = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)

    // ─── Detail deps ───
    private val mangaRepo = mockk<MangaRepository>(relaxed = true)
    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val downloadQueueManager = mockk<DownloadQueueManager>(relaxed = true)
    private val firebaseSyncManager = mockk<FirebaseSyncManager>(relaxed = true)
    private val firebaseTopicManager = mockk<FirebaseTopicManager>(relaxed = true)
    private val widgetShortcutCoordinator = mockk<WidgetShortcutCoordinator>(relaxed = true)
    private val analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
    private val firebaseTelemetry = mockk<FirebaseTelemetry>(relaxed = true)

    // ─── Reader-only deps ───
    private val cacheDao = mockk<MangaCacheDao>(relaxed = true)
    private val readingStatsStore = mockk<ReadingStatsStore>(relaxed = true)
    private val achievementManager = mockk<AchievementManager>(relaxed = true)
    private val imagePrefetcher = mockk<ImagePrefetcher>(relaxed = true)
    private val remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true)
    private val positionSyncManager = mockk<ReadingPositionSyncManager>(relaxed = true)
    private val imageLoader = mockk<coil.ImageLoader>(relaxed = true)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Shared stubbing ───

    private fun stubDetailCommon() {
        every { libraryRepo.observeLibraryEntry(any()) } returns flowOf(null)
        every { libraryRepo.getReadChapters(any()) } returns flowOf(emptySet())
        coEvery { libraryRepo.getReadingProgressMap(any()) } returns emptyMap()
        every { downloadQueueManager.isChapterDownloaded(any(), any()) } returns false
        every { downloadQueueManager.observeTasks() } returns flowOf(emptyList())
        every { downloadQueueManager.getMangaDirPath(any()) } returns "/nonexistent_mw_test_dir_xyz"
        every { communityRepo.observeUserLists() } returns flowOf(emptyList())
        coEvery { mangaRepo.searchMangaDirect(any(), any()) } returns Result.success(emptyList())
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
    }

    private fun stubReaderCommon() {
        every { settingsRepo.getReaderSettings() } returns flowOf(ReaderSettings())
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { libraryRepo.observeReaderAnnotations(any(), any()) } returns flowOf(emptyList())
        every { communityRepo.observeReaderPresenceCount(any(), any()) } returns flowOf(0)
        every { communityRepo.observePageReactions(any(), any(), any()) } returns flowOf(emptyList())
        every { communityRepo.observeChapterComments(any(), any()) } returns flowOf(emptyList())
        coEvery { cacheDao.get(any()) } returns null
        every { downloadQueueManager.getLocalChapterPages(any(), any()) } returns emptyList()
    }

    private fun createDetailViewModel() = MangaDetailViewModel(
        context = context,
        mangaRepo = mangaRepo,
        libraryRepo = libraryRepo,
        communityRepository = communityRepo,
        settingsRepo = settingsRepo,
        downloadQueueManager = downloadQueueManager,
        firebaseSyncManager = firebaseSyncManager,
        firebaseTopicManager = firebaseTopicManager,
        widgetShortcutCoordinator = widgetShortcutCoordinator,
        analyticsManager = analyticsManager,
        firebaseTelemetry = firebaseTelemetry
    )

    private fun createReaderViewModel() = ReaderViewModel(
        context = context,
        mangaRepo = mangaRepo,
        libraryRepo = libraryRepo,
        settingsRepo = settingsRepo,
        communityRepository = communityRepo,
        downloadQueueManager = downloadQueueManager,
        cacheDao = cacheDao,
        readingStatsStore = readingStatsStore,
        achievementManager = achievementManager,
        imagePrefetcher = imagePrefetcher,
        firebaseSyncManager = firebaseSyncManager,
        widgetShortcutCoordinator = widgetShortcutCoordinator,
        analyticsManager = analyticsManager,
        remoteConfigManager = remoteConfigManager,
        positionSyncManager = positionSyncManager,
        imageLoader = imageLoader
    )

    /**
     * Polls state until [check] passes; needed where the VM hops to real
     * Dispatchers.IO. Uses runCurrent(), NEVER advanceUntilIdle(): the reader
     * starts a `while(true){delay(30s)}` session-saver on load, and advancing
     * virtual time loops it forever (hung CI for 30+ min).
     */
    private fun TestScope.awaitUntil(timeoutMs: Long = 5_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for VM state")
            Thread.sleep(25)
            runCurrent()
        }
    }

    private fun testChapter(number: Float) = Chapter(
        id = "c$number",
        mangaId = "azora_test-slug",
        number = number,
        title = "Chapter $number",
        url = "https://example.com/c$number"
    )

    private fun testDetail() = MangaDetail(
        id = "azora_test-slug",
        slug = "test-slug",
        title = "Test Manga",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA,
        totalChapters = 3,
        chapters = listOf(testChapter(1f), testChapter(2f), testChapter(3f))
    )

    private fun testPages() = listOf(
        ChapterPage(index = 0, url = "https://example.com/p0.jpg"),
        ChapterPage(index = 1, url = "https://example.com/p1.jpg"),
        ChapterPage(index = 2, url = "https://example.com/p2.jpg")
    )

    // ─── Detail ───

    @Test
    fun detailLoad_populatesMangaAndChapters() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns Result.success(testDetail())
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { vm.state.value.manga != null }
            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals("Test Manga", state.manga?.title)
            assertEquals(3, state.manga?.chapters?.size)
            assertNull(state.error)
            coVerify { mangaRepo.getMangaDetail("test-slug", MangaSource.AZORA) }
        }
    }

    @Test
    fun detailLoad_failureSurfacesGenericError() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns
                Result.failure(Exception("backend-boom-raw-9371"))
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { !vm.state.value.isLoading }
            val state = vm.state.value
            assertNull(state.manga)
            assertNotNull(state.error)
            // Raw backend text must never reach UI state (generic download_error instead).
            assertFalse(state.error!!.contains("backend-boom-raw-9371"))
            assertNull(state.cloudflareUrl)
        }
    }

    @Test
    fun detailToggleFavorite_forwardsAddToRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns Result.success(testDetail())
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { vm.state.value.manga != null }
            assertFalse(vm.state.value.isFavorite)
            vm.toggleFavorite()
            advanceUntilIdle()
            coVerify { libraryRepo.addFavorite(match { it.mangaId == "azora_test-slug" }) }
        }
    }

    @Test
    fun detailToggleFavorite_forwardsRemoveToRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            every { libraryRepo.observeLibraryEntry(any()) } returns flowOf(
                FavoriteManga(
                    mangaId = "azora_test-slug",
                    slug = "test-slug",
                    title = "Test Manga",
                    coverUrl = "https://example.com/cover.jpg",
                    source = MangaSource.AZORA,
                    isFavorite = true
                )
            )
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns Result.success(testDetail())
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { vm.state.value.isFavorite }
            vm.toggleFavorite()
            advanceUntilIdle()
            coVerify { libraryRepo.removeFavorite("azora_test-slug") }
        }
    }

    @Test
    fun detailChaptersOrder_flipsSortAndSearchFilters() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns Result.success(testDetail())
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { vm.state.value.manga != null }
            // Default is newest-first.
            assertEquals(listOf(3f, 2f, 1f), vm.sortedChapters().map { it.number })
            vm.toggleChaptersOrder()
            assertEquals(listOf(1f, 2f, 3f), vm.sortedChapters().map { it.number })
            // Search filters on display number (sync pure helper).
            vm.updateChapterSearchQuery("2")
            val filtered = vm.getFilteredChapters()
            assertEquals(1, filtered.size)
            assertEquals(2f, filtered.first().number)
            vm.updateChapterSearchQuery("")
            assertEquals(3, vm.getFilteredChapters().size)
        }
    }

    @Test
    fun detailMarkChapterAsRead_updatesStateAndRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubDetailCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns Result.success(testDetail())
            val vm = createDetailViewModel()
            vm.load("test-slug", MangaSource.AZORA)
            awaitUntil { vm.state.value.manga != null }
            vm.markChapterAsRead(testChapter(2f))
            advanceUntilIdle()
            coVerify { libraryRepo.markChapterRead("azora_test-slug", 2f) }
            assertTrue(vm.state.value.readChapters.contains(2f))
        }
    }

    // ─── Reader ───

    @Test
    fun readerInitialState_hasSaneDefaults() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            val vm = createReaderViewModel()
            runCurrent()
            val state = vm.state.value
            assertTrue(state.isLoading)
            assertTrue(state.pages.isEmpty())
            assertEquals(0, state.currentPage)
            assertTrue(state.showControls)
            assertEquals(1.0f, state.brightness)
            assertEquals(ReaderMode.VERTICAL_SCROLL, state.readerMode)
            assertNull(state.error)
            assertNull(state.prevChapterUrl)
            assertNull(state.nextChapterUrl)
            assertFalse(state.downloadInProgress)
            assertFalse(state.lastDownloadFailed)
            assertEquals(0.5f, state.lastTapNormalizedX)
            assertEquals(0.5f, state.lastTapNormalizedY)
        }
    }

    @Test
    fun readerLoadChapter_successPopulatesPagesAndChapterNumber() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns
                Result.failure(Exception("no-meta"))
            coEvery { mangaRepo.getChapterPages(any(), any(), any()) } returns
                Result.success(testPages())
            coEvery { libraryRepo.getReadingProgress(any(), any()) } returns Pair(0, 0)
            val vm = createReaderViewModel()
            runCurrent()
            vm.loadChapter("https://example.com/ch-5", "azora_test-slug", MangaSource.AZORA)
            awaitUntil { !vm.state.value.isLoading && vm.state.value.pages.isNotEmpty() }
            val state = vm.state.value
            assertEquals(3, state.pages.size)
            assertEquals(3, state.totalPages)
            assertEquals(0, state.currentPage)
            assertEquals("https://example.com/ch-5", state.chapterUrl)
            // No chapter meta resolved → fallback number parsed from the URL.
            assertEquals(5f, state.chapterNumber)
            assertNull(state.error)
            // Single chapter loaded with no catalogue: neighbour navigation is a no-op.
            vm.openNextChapter()
            vm.openPreviousChapter()
            runCurrent()
            assertEquals("https://example.com/ch-5", vm.state.value.chapterUrl)
        }
    }

    @Test
    fun readerLoadChapter_failureSurfacesGenericError() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns
                Result.failure(Exception("no-meta"))
            coEvery { mangaRepo.getChapterPages(any(), any(), any()) } returns
                Result.failure(Exception("reader-boom-raw-4521"))
            val vm = createReaderViewModel()
            runCurrent()
            vm.loadChapter("https://example.com/ch-9", "azora_test-slug", MangaSource.AZORA)
            awaitUntil { !vm.state.value.isLoading }
            val state = vm.state.value
            assertTrue(state.pages.isEmpty())
            assertNotNull(state.error)
            assertFalse(state.error!!.contains("reader-boom-raw-4521"))
        }
    }

    @Test
    fun readerLoadChapter_restoresSavedProgress() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns
                Result.failure(Exception("no-meta"))
            coEvery { mangaRepo.getChapterPages(any(), any(), any()) } returns
                Result.success(testPages())
            coEvery { libraryRepo.getReadingProgress(any(), any()) } returns Pair(2, 5)
            val vm = createReaderViewModel()
            runCurrent()
            vm.loadChapter("https://example.com/ch-5", "azora_test-slug", MangaSource.AZORA)
            awaitUntil { !vm.state.value.isLoading && vm.state.value.pages.isNotEmpty() }
            assertEquals(2, vm.state.value.currentPage)
            assertEquals(2, vm.state.value.pageInChapter)
            assertEquals(3, vm.state.value.chapterPageCount)
        }
    }

    @Test
    fun readerTap_verticalModeTogglesControlsAndClamps() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            val vm = createReaderViewModel()
            runCurrent()
            assertTrue(vm.state.value.showControls)
            // Vertical mode: any tap toggles controls and records the tap point.
            vm.onReaderTap(0.1f, 0.5f)
            assertFalse(vm.state.value.showControls)
            assertEquals(0.1f, vm.state.value.lastTapNormalizedX)
            assertEquals(0.5f, vm.state.value.lastTapNormalizedY)
            // Out-of-range taps are clamped into [0, 1].
            vm.onReaderTap(2f, -1f)
            assertTrue(vm.state.value.showControls)
            assertEquals(1f, vm.state.value.lastTapNormalizedX)
            assertEquals(0f, vm.state.value.lastTapNormalizedY)
            // No chapter ranges loaded: in-chapter seek is a silent no-op.
            vm.seekToPageInChapter(2)
            assertEquals(0, vm.state.value.currentPage)
        }
    }

    @Test
    fun readerDownloadFailedToken_setsTypedSignalWithoutLeakingToken() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubReaderCommon()
            coEvery { mangaRepo.getMangaDetail(any(), any()) } returns
                Result.failure(Exception("no-meta"))
            coEvery { mangaRepo.getChapterPages(any(), any(), any()) } returns
                Result.success(testPages())
            coEvery { libraryRepo.getReadingProgress(any(), any()) } returns Pair(0, 0)
            val failedTask = DownloadTaskEntity(
                id = "task-1",
                mangaId = "azora_test-slug",
                chapterUrl = "https://example.com/ch-5",
                targetDir = "/tmp/mw-test",
                status = "failed",
                progress = 0.25f,
                errorMessage = "cancelled"
            )
            coEvery {
                downloadQueueManager.enqueueAndRun(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns true
            every { downloadQueueManager.observeTask(any()) } returns flowOf(failedTask)
            val vm = createReaderViewModel()
            runCurrent()
            vm.loadChapter("https://example.com/ch-5", "azora_test-slug", MangaSource.AZORA)
            awaitUntil { !vm.state.value.isLoading && vm.state.value.pages.isNotEmpty() }
            vm.downloadCurrentChapter()
            awaitUntil { vm.state.value.lastDownloadFailed }
            val state = vm.state.value
            // Typed signal drives the retry affordance — never string comparison.
            assertTrue(state.lastDownloadFailed)
            assertFalse(state.downloadInProgress)
            assertEquals("task-1", state.activeDownloadTaskId)
            // Stable stored token is translated, never shown verbatim.
            assertNotEquals("cancelled", state.downloadMessage)
            vm.cancelDownload()
            runCurrent()
            coVerify { downloadQueueManager.cancelTask("task-1") }
        }
    }
}
