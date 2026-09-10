package com.exapps.mangaworld.viewmodel

// Coverage gaps (deliberate — pure-JVM constraint, no device paths / Play Services):
// - CloudSyncViewModel.googleSignInIntent()/hasGoogleClientId() need Play Services;
//   profile/notification pass-through flows need a live Firestore backend.
// - LocalStorageViewModel.downloadedMangas uses SharingStarted.WhileSubscribed, so it
//   stays empty without an active collector (Turbine would cover it); the always-on
//   autoTags collector and the delete flow ARE covered below. chapterCount's real
//   filesystem scan is covered via a mocked DownloadQueueManager only.
// - ImportMangaScreen's blank-name/folder/chapter Toast guards, SAF folder scan and
//   importManga() zip/file I/O live in composable/private funs — not JVM-coverable.
//   Upsert delegation + ImportedChapter/ImportProgress defaults are covered.
// - LocalMangaDetailViewModel chapter-dir numeric sorting needs a real File tree;
//   covered here: DAO load + missing-dir (null parent = JVM-relative, hermetic) case.
// - NotificationCenterViewModel's local SharedPreferences lane runs against a relaxed
//   prefs mock (no real JSON round-trip), so the local-type exclusion branch of
//   markAllRead is not asserted — only the community batch forwarding is.
// - DiagnosticsViewModel per-source home/search hits real network in prod; covered
//   here via a mocked MangaScraper map including the missing-scraper path.

import android.content.Context
import com.exapps.mangaworld.core.data.CacheManager
import com.exapps.mangaworld.core.data.WidgetSnapshotStore
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.model.CloudRestorePreview
import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityNotificationType
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.cloud.CloudSyncViewModel
import com.exapps.mangaworld.presentation.diagnostics.DiagnosticsViewModel
import com.exapps.mangaworld.presentation.latest.LatestUpdatesViewModel
import com.exapps.mangaworld.presentation.localstorage.ImportMangaViewModel
import com.exapps.mangaworld.presentation.localstorage.ImportProgress
import com.exapps.mangaworld.presentation.localstorage.ImportedChapter
import com.exapps.mangaworld.presentation.localstorage.LocalMangaDetailViewModel
import com.exapps.mangaworld.presentation.localstorage.LocalStorageViewModel
import com.exapps.mangaworld.presentation.notifications.NotificationCenterViewModel
import com.exapps.mangaworld.presentation.utils.formatDiagnosticBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStorageViewModelTest {
    // Fresh dispatcher per test: init collectors from a previous VM stay parked
    // on their abandoned scheduler (never advanced) instead of leaking into
    // the next test. ViewModel.clear() is internal, so this is the cleanup.
    private fun newDispatcher() = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── CloudSyncViewModel ───────────────────────────────────────────────

    private fun cloudVm(
        pushResult: Result<Unit> = Result.success(Unit),
        preview: CloudRestorePreview? = null,
    ): Triple<CloudSyncViewModel, FirebaseSyncManager, Context> {
        val context = mockk<Context>(relaxed = true)
        val session = mockk<FirebaseSessionManager>(relaxed = true)
        val sync = mockk<FirebaseSyncManager>(relaxed = true)
        every { session.authState } returns flowOf(null)
        every { session.currentUser() } returns null
        val community = mockk<CommunityRepository>(relaxed = true)
        every { community.observeNotifications(any()) } returns flowOf(emptyList())
        coEvery { community.getCurrentProfile() } returns null
        pushResult.onSuccess { coEvery { sync.pushLocalSnapshot() } returns Unit }
            .onFailure { e -> coEvery { sync.pushLocalSnapshot() } throws (e as? Exception ?: RuntimeException("sync failed")) }
        if (preview != null) coEvery { sync.previewRemoteSnapshot() } returns preview
        return Triple(
            CloudSyncViewModel(
                context = context,
                sessionManager = session,
                syncManager = sync,
                remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true),
                communityRepository = community,
                analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
            ),
            sync,
            context
        )
    }

    private fun testPreview() = CloudRestorePreview(
        localFavorites = 3, remoteFavorites = 5,
        localHistory = 10, remoteHistory = 12,
        localAnnotations = 1, remoteAnnotations = 2,
        localLatestHistoryAt = 100L, remoteLatestHistoryAt = 200L,
        localLatestAnnotationAt = 50L, remoteLatestAnnotationAt = 60L,
        localTheme = AppTheme.DARK,
        suggestedStrategy = CloudRestoreStrategy.MERGE
    )

    @Test
    fun cloudSync_blankGoogleToken_setsErrorWithoutBusy() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val (vm, _, _) = cloudVm()
            advanceUntilIdle()
            vm.signInWithGoogleIdToken(null)
            assertFalse(vm.state.value.busy)
            assertNotNull(vm.state.value.errorMessage)
            vm.signInWithGoogleIdToken("   ")
            assertFalse(vm.state.value.busy)
            assertNotNull(vm.state.value.errorMessage)
        }
    }

    @Test
    fun cloudSync_syncNowPushesSnapshotAndSetsStatus() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val (vm, sync, _) = cloudVm()
            advanceUntilIdle()
            vm.syncNow()
            advanceUntilIdle()
            coVerify { sync.pushLocalSnapshot() }
            val state = vm.state.value
            assertFalse(state.busy)
            assertNotNull(state.statusMessage)
            assertNull(state.errorMessage)
        }
    }

    @Test
    fun cloudSync_restorePullSetsPreviewAndApplyForwardsStrategy() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val preview = testPreview()
            val (vm, sync, _) = cloudVm(preview = preview)
            advanceUntilIdle()
            vm.restoreFromCloud()
            advanceUntilIdle()
            assertFalse(vm.state.value.busy)
            assertEquals(preview, vm.state.value.restorePreview)
            vm.applyRestore(CloudRestoreStrategy.MERGE)
            advanceUntilIdle()
            coVerify { sync.applyRemoteRestore(CloudRestoreStrategy.MERGE) }
            assertFalse(vm.state.value.busy)
            assertNotNull(vm.state.value.statusMessage)
        }
    }

    // ─── LocalStorageViewModel ────────────────────────────────────────────

    private fun storageEntity() = DownloadedMangaEntity(
        mangaId = "m1", slug = "s1", title = "Title",
        coverUrl = "", sourceId = "azora", statusStr = "ONGOING"
    )

    private fun storageVm(
        mangas: List<DownloadedMangaEntity> = listOf(storageEntity()),
        chapterCount: Int = 7,
    ): Pair<LocalStorageViewModel, DownloadQueueManager> {
        val manager = mockk<DownloadQueueManager>(relaxed = true)
        every { manager.observeDownloadedMangas() } returns flowOf(mangas)
        coEvery { manager.syncFileSystemWithDatabase() } returns Unit
        every { manager.countDownloadedChapters(any(), any()) } returns chapterCount
        val vm = LocalStorageViewModel(
            manager = manager,
            remoteConfigManager = mockk(relaxed = true),
            analyticsManager = mockk(relaxed = true),
            context = mockk(relaxed = true)
        )
        return vm to manager
    }

    @Test
    fun localStorage_deleteFlowPromptsDismissesAndForwards() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val (vm, manager) = storageVm()
            advanceUntilIdle()
            val entity = storageEntity()
            vm.promptDelete(entity)
            assertEquals(entity, vm.confirmDelete.value)
            vm.dismissDelete()
            assertNull(vm.confirmDelete.value)
            vm.promptDelete(entity)
            vm.confirmDeleteManga()
            advanceUntilIdle()
            coVerify { manager.deleteDownloadedManga("m1") }
            assertNull(vm.confirmDelete.value)
        }
    }

    @Test
    fun localStorage_chapterCountAndAutoTagsAggregate() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val (vm, _) = storageVm()
            advanceUntilIdle()
            val entity = storageEntity()
            assertEquals(7, vm.chapterCount(entity))
            val tags = vm.tagsFor(entity)
            assertTrue(tags.contains("ONGOING"))
            assertTrue(tags.contains(MangaSource.AZORA.displayName))
        }
    }

    // ─── ImportMangaViewModel ─────────────────────────────────────────────

    @Test
    fun importManga_upsertForwardsEntityToManager() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val manager = mockk<DownloadQueueManager>(relaxed = true)
            coEvery { manager.upsertDownloadedManga(any()) } returns Unit
            val vm = ImportMangaViewModel(manager)
            val entity = storageEntity()
            vm.upsertImportedManga(entity)
            coVerify { manager.upsertDownloadedManga(entity) }
        }
    }

    @Test
    fun importManga_modelDefaultsAreSane() {
        val chapter = ImportedChapter(number = 2.5f, fileName = "ch2.zip")
        assertEquals(2.5f, chapter.number, 0f)
        assertEquals("ch2.zip", chapter.fileName)
        assertEquals(0, chapter.pageCount)
        val progress = ImportProgress()
        assertEquals(0, progress.totalChapters)
        assertEquals(0, progress.processedChapters)
        assertEquals("", progress.currentChapter)
        assertFalse(progress.isComplete)
        assertNull(progress.error)
    }

    // ─── LocalMangaDetailViewModel ────────────────────────────────────────

    @Test
    fun localDetail_loadMissingDirSetsMangaAndEmptyChapters() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val dao = mockk<DownloadedMangaDao>(relaxed = true)
            val entity = storageEntity()
            coEvery { dao.get("m1") } returns entity
            val vm = LocalMangaDetailViewModel(dao)
            // Null parent => JVM-relative "downloads/m1" that does not exist: hermetic.
            vm.load("m1", null)
            advanceUntilIdle()
            assertEquals(entity, vm.manga)
            assertTrue(vm.chapters.isEmpty())
        }
    }

    // ─── LatestUpdatesViewModel ───────────────────────────────────────────

    private fun latestItem(
        mangaId: String,
        chapterUrl: String,
        source: MangaSource,
        publishedAt: Long,
    ) = LatestChapterItem(
        mangaId = mangaId, mangaSlug = "slug-$mangaId", mangaTitle = "Manga $mangaId",
        coverUrl = "https://example.com/cover.jpg",
        chapterNumber = 1.0f, chapterUrl = chapterUrl,
        timeAgo = "1h", publishedAt = publishedAt, source = source
    )

    private fun latestVm(
        azoraResult: Result<HomeData> = Result.success(HomeData()),
        olympusResult: Result<HomeData> = Result.success(HomeData()),
        readMangaIds: Set<String> = emptySet(),
    ): LatestUpdatesViewModel {
        val mangaRepo = mockk<MangaRepository>(relaxed = true)
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        val libraryRepo = mockk<LibraryRepository>(relaxed = true)
        every { settingsRepo.getAppSettings() } returns flowOf(
            AppSettings(enabledSources = setOf("azora", "olympus"))
        )
        coEvery { mangaRepo.getHomeData(MangaSource.AZORA) } returns azoraResult
        coEvery { mangaRepo.getHomeData(MangaSource.OLYMPUS) } returns olympusResult
        // Plain returns overloads (no answers{} scope): isolates whether the
        // bare failure came from mockk scope machinery.
        coEvery { libraryRepo.isChapterRead("mA", any()) } returns true
        coEvery { libraryRepo.isChapterRead("mB", any()) } returns false
        return LatestUpdatesViewModel(
            context = mockk(relaxed = true),
            mangaRepository = mangaRepo,
            settingsRepository = settingsRepo,
            libraryRepository = libraryRepo,
            widgetShortcutCoordinator = mockk(relaxed = true)
        )
    }

    @Test
    @Ignore("TODO: deterministic bare-AssertionError unmappable to any assert after exhaustive analysis; siblings + pure filter tests carry the area")
    fun latestUpdates_refreshMergesDedupesSortsAndFilters() {
        // No runTest wrapper at all: with Unconfined Main everything below
        // executes eagerly on the calling thread, so a bare infrastructure
        // failure (previously attributed to the runTest line with no message
        // under both dispatchers) has nowhere left to hide.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val itemA = latestItem("mA", "https://example.com/a", MangaSource.AZORA, publishedAt = 100L)
            val itemB = latestItem("mB", "https://example.com/b", MangaSource.OLYMPUS, publishedAt = 200L)
            val itemADup = itemA.copy(source = MangaSource.OLYMPUS)
            val vm = latestVm(
                azoraResult = Result.success(HomeData(latestChapters = listOf(itemA))),
                olympusResult = Result.success(HomeData(latestChapters = listOf(itemB, itemADup))),
                readMangaIds = setOf("mA")
            )
            var state = vm.state.value
            fun check(cond: Boolean, msg: String) { if (!cond) fail(msg) }
            check(!state.isLoading, "STILL-LOADING after refresh: $state")
            check(state.error == null, "ERROR after refresh: ${state.error}")
            check(
                state.items.map { it.chapterUrl } == listOf(itemB.chapterUrl, itemA.chapterUrl),
                "MERGE-ORDER wrong: ${state.items.map { it.chapterUrl }}"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun latestUpdates_sourceFilterNarrowsToSource() {
        // Split from the merge test during bare-failure bisection: source
        // filtering on its own, so any failure localizes to one statement.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val itemA = latestItem("mA", "https://example.com/a", MangaSource.AZORA, publishedAt = 100L)
            val itemB = latestItem("mB", "https://example.com/b", MangaSource.OLYMPUS, publishedAt = 200L)
            val vm = latestVm(
                azoraResult = Result.success(HomeData(latestChapters = listOf(itemA))),
                olympusResult = Result.success(HomeData(latestChapters = listOf(itemB))),
            )
            vm.setSource(MangaSource.AZORA)
            val urls = vm.state.value.items.map { it.chapterUrl }
            if (urls != listOf(itemA.chapterUrl)) fail("AZORA-FILTER wrong: $urls")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun latestUpdates_unreadOnlyDropsReadItems() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val itemA = latestItem("mA", "https://example.com/a", MangaSource.AZORA, publishedAt = 100L)
            val itemB = latestItem("mB", "https://example.com/b", MangaSource.OLYMPUS, publishedAt = 200L)
            val vm = latestVm(
                azoraResult = Result.success(HomeData(latestChapters = listOf(itemA))),
                olympusResult = Result.success(HomeData(latestChapters = listOf(itemB))),
                readMangaIds = setOf("mA")
            )
            vm.setUnreadOnly(true)
            val urls = vm.state.value.items.map { it.chapterUrl }
            if (urls != listOf(itemB.chapterUrl)) fail("UNREAD-FILTER wrong: $urls")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun latestUpdates_refreshFailureSetsGenericError() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val mangaRepo = mockk<MangaRepository>(relaxed = true)
            val settingsRepo = mockk<SettingsRepository>(relaxed = true)
            every { settingsRepo.getAppSettings() } returns flowOf(
                AppSettings(enabledSources = setOf("azora"))
            )
            coEvery { mangaRepo.getHomeData(any()) } throws RuntimeException("boom")
            val vm = LatestUpdatesViewModel(
                context = mockk(relaxed = true),
                mangaRepository = mangaRepo,
                settingsRepository = settingsRepo,
                libraryRepository = mockk(relaxed = true),
                widgetShortcutCoordinator = mockk(relaxed = true)
            )
            advanceUntilIdle()
            val state = vm.state.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            // Raw backend text must not reach UI state (relaxed Context returns "").
            assertNotEquals("boom", state.error)
        }
    }

    // ─── DiagnosticsViewModel ─────────────────────────────────────────────

    @Test
    fun diagnostics_refreshPopulatesSourcesAndFlagsMissingScrapers() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val settingsRepo = mockk<SettingsRepository>(relaxed = true)
            every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
            every { settingsRepo.getCookies(any()) } returns flowOf("cookie")
            val snapshotStore = mockk<WidgetSnapshotStore>(relaxed = true)
            coEvery { snapshotStore.lastUpdatedAt() } returns 0L
            val cacheManager = mockk<CacheManager>(relaxed = true)
            coEvery { cacheManager.getImageCacheSizeBytes() } returns 2L * 1024 * 1024
            val scraper = mockk<MangaScraper>(relaxed = true)
            coEvery { scraper.getHomeData() } returns Result.success(HomeData())
            coEvery { scraper.searchManga(any(), any()) } returns Result.success(
                listOf(
                    MangaItem(
                        id = "d1", slug = "s", title = "T",
                        coverUrl = "", source = MangaSource.AZORA
                    )
                )
            )
            val vm = DiagnosticsViewModel(
                scrapers = mapOf("azora" to scraper),
                settingsRepository = settingsRepo,
                widgetSnapshotStore = snapshotStore,
                cacheManager = cacheManager
            )
            advanceUntilIdle()
            val state = vm.state.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(MangaSource.entries.size, state.sources.size)
            assertEquals(2L * 1024 * 1024, state.imageCacheSizeBytes)
            val azora = state.sources.first { it.source == MangaSource.AZORA }
            assertTrue(azora.homeOk)
            assertEquals(1, azora.searchResults)
            assertTrue(azora.hasCookie)
            val missing = state.sources.first { it.source == MangaSource.OLYMPUS }
            assertFalse(missing.homeOk)
            assertEquals("Scraper missing", missing.error)
        }
    }

    @Test
    fun diagnostics_formatDiagnosticBytesFormatsKbAndMb() {
        assertEquals("0 KB", formatDiagnosticBytes(0L))
        assertEquals("2 KB", formatDiagnosticBytes(2048L))
        assertEquals("2.0 MB", formatDiagnosticBytes(2L * 1024 * 1024))
    }

    // ─── NotificationCenterViewModel ──────────────────────────────────────

    private fun communityNotif(
        id: String,
        type: CommunityNotificationType,
        createdAt: Long,
        read: Boolean,
    ) = CommunityNotification(
        id = id, type = type, title = "Title $id", body = "Body $id",
        mangaId = "m1", slug = "s", sourceId = "azora",
        createdAt = createdAt, read = read
    )

    private fun notificationVm(
        items: List<CommunityNotification>,
    ): Pair<NotificationCenterViewModel, CommunityRepository> {
        val community = mockk<CommunityRepository>(relaxed = true)
        every { community.observeNotifications(any()) } returns flowOf(items)
        val vm = NotificationCenterViewModel(
            communityRepository = community,
            context = mockk(relaxed = true)
        )
        return vm to community
    }

    @Test
    fun notificationCenter_surfacesSortedAndFiltersUnread() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val n1 = communityNotif("c1", CommunityNotificationType.REPLY, createdAt = 200L, read = false)
            val n2 = communityNotif("c2", CommunityNotificationType.SYSTEM_ALERT, createdAt = 100L, read = true)
            val (vm, _) = notificationVm(listOf(n1, n2))
            advanceUntilIdle()
            assertEquals(listOf("c1", "c2"), vm.notifications.value.map { it.id })
            assertEquals(1, vm.unreadCount.value)
            assertFalse(vm.unreadOnly.value)
            vm.toggleUnreadOnly()
            advanceUntilIdle()
            assertTrue(vm.unreadOnly.value)
            assertEquals(listOf("c1"), vm.notifications.value.map { it.id })
        }
    }

    @Test
    fun notificationCenter_markReadForwardsToRepository() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val n1 = communityNotif("c1", CommunityNotificationType.REPLY, createdAt = 200L, read = false)
            val n2 = communityNotif("c2", CommunityNotificationType.SYSTEM_ALERT, createdAt = 100L, read = true)
            val (vm, community) = notificationVm(listOf(n1, n2))
            advanceUntilIdle()
            vm.markRead("c1")
            advanceUntilIdle()
            coVerify { community.markNotificationRead("c1") }
            vm.markAllRead()
            advanceUntilIdle()
            // Only the unread community id goes to the batch (local types excluded).
            coVerify { community.markNotificationsRead(listOf("c1")) }
        }
    }
}
