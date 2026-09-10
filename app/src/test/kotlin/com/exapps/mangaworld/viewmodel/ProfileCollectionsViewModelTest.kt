package com.exapps.mangaworld.viewmodel

// Coverage for the screen-local profile / collections / goals / more ViewModels.
// Hermetic JVM tests: every repo/store/manager is a relaxed mockk; only pure
// model math (ReadingGoal.progressPercent) runs on real code.
//
// NOT covered here and why:
// - UserProfileViewModel.uploadAvatar/uploadBanner: need android.net.Uri plus
//   CloudinaryUploader network results; Uri cannot be constructed on JVM and a
//   mocked Uri proves nothing about the real upload/delete-old-image path.
// - UserListsViewModel.uploadCover: same Uri + Cloudinary reason as above.
// - ProfileSettingsViewModel firestore aggregate counts (comments/reviews) and
//   googleSignInIntent/linkGoogle/linkFacebook/unlinkProvider: they await real
//   Firebase Task objects / GoogleSignIn clients, which are unmockable here.
//   signOut/deleteAccount happy paths are covered via sessionManager/auth mocks.
// - PublicProfileViewModel own-profile readingLists: same loading loop as
//   UserProfileViewModel (covered there); here the foreign-profile path is used.
// - Goals progress auto-update: lives inside AchievementManager (DataStore +
//   wall-clock periods); only the pure progressPercent math is asserted here.
// - Blank-name guard on list/collection/goal creation dialogs is UI-side
//   (button enabled = name.isNotBlank()); VMs forward whatever they receive.

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.exapps.mangaworld.core.data.AchievementManager
import com.exapps.mangaworld.core.data.CollectionManager
import com.exapps.mangaworld.core.data.GoalPeriod
import com.exapps.mangaworld.core.data.GoalType
import com.exapps.mangaworld.core.data.MangaCollection
import com.exapps.mangaworld.core.data.ReadingGoal
import com.exapps.mangaworld.core.data.ReadingStatsStore
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.entity.MangaCacheEntity
import com.exapps.mangaworld.core.firebase.CloudinaryUploader
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.model.FavoriteManga
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.collections.CollectionDetailViewModel
import com.exapps.mangaworld.presentation.collections.CollectionsViewModel
import com.exapps.mangaworld.presentation.goals.GoalsViewModel
import com.exapps.mangaworld.presentation.more.MoreViewModel
import com.exapps.mangaworld.presentation.profile.ProfileSettingsViewModel
import com.exapps.mangaworld.presentation.profile.PublicProfileViewModel
import com.exapps.mangaworld.presentation.profile.UserListsViewModel
import com.exapps.mangaworld.presentation.profile.UserProfileViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCollectionsViewModelTest {
    // Fresh dispatcher per test: init collectors from a previous VM stay parked
    // on their abandoned scheduler (never advanced) instead of leaking into
    // the next test. ViewModel.clear() is internal, so this is the cleanup.
    private fun newDispatcher() = StandardTestDispatcher()

    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val readingStatsStore = mockk<ReadingStatsStore>(relaxed = true)
    private val achievementManager = mockk<AchievementManager>(relaxed = true)
    private val collectionManager = mockk<CollectionManager>(relaxed = true)
    private val cloudinaryUploader = mockk<CloudinaryUploader>(relaxed = true)
    private val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
    private val favoriteDao = mockk<FavoriteDao>(relaxed = true)
    private val historyDao = mockk<ReadingHistoryDao>(relaxed = true)
    private val readChapterDao = mockk<ReadChapterDao>(relaxed = true)
    private val mangaCacheDao = mockk<MangaCacheDao>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── shared stubs ────────────────────────────────────────────────────────

    private fun stubUserProfileVm() {
        coEvery { communityRepo.getCurrentProfile() } returns testProfile()
        every { communityRepo.observeNotifications(any()) } returns flowOf(emptyList())
        every { communityRepo.observeUserLists() } returns flowOf(emptyList())
        coEvery { libraryRepo.getFavoritesByStatus(any()) } returns listOf(testFavorite("m1"))
        every { readingStatsStore.totalReadingTimeMs } returns flowOf(0L)
        every { readingStatsStore.totalMangaRead } returns flowOf(0)
        every { readingStatsStore.currentStreak } returns flowOf(0)
        coEvery { achievementManager.getUnlockedAchievementCount() } returns 2
    }

    private fun stubProfileSettingsVm() {
        every { auth.currentUser } returns null
        coEvery { communityRepo.getCurrentProfile() } returns testProfile(username = "old")
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { settingsRepo.getFavoriteGenres() } returns flowOf(emptyList())
        every { communityRepo.getBlockedUsers() } returns flowOf(emptySet())
        every { sessionManager.authState } returns flowOf(null)
        every { sessionManager.currentUserId() } returns null
        every { sessionManager.linkedProviderIds() } returns emptySet()
        every { readingStatsStore.totalReadingTimeMs } returns flowOf(0L)
        every { readingStatsStore.totalMangaRead } returns flowOf(0)
        every { readingStatsStore.currentStreak } returns flowOf(0)
        coEvery { favoriteDao.getFavoritesList() } returns emptyList()
        coEvery { historyDao.getAll() } returns emptyList()
        coEvery { readChapterDao.getTotalReadCount() } returns 0
    }

    private fun createUserProfileVm() = UserProfileViewModel(
        communityRepository = communityRepo,
        libraryRepository = libraryRepo,
        readingStatsStore = readingStatsStore,
        achievementManager = achievementManager,
        cloudinaryUploader = cloudinaryUploader
    )

    private fun createProfileSettingsVm() = ProfileSettingsViewModel(
        communityRepository = communityRepo,
        settingsRepository = settingsRepo,
        sessionManager = sessionManager,
        readingStatsStore = readingStatsStore,
        favoriteDao = favoriteDao,
        historyDao = historyDao,
        readChapterDao = readChapterDao,
        cloudinaryUploader = cloudinaryUploader,
        auth = auth,
        firestore = firestore,
        context = context
    )

    // ─── UserProfileViewModel ────────────────────────────────────────────────

    @Test
    fun userProfile_loadClearsLoadingAndLoadsReadingLists() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        stubUserProfileVm()
        val vm = createUserProfileVm()
        advanceUntilIdle()
        assertFalse(vm.isLoading.value)
        assertEquals(
            setOf("reading", "completed", "plan_to_read", "on_hold", "dropped"),
            vm.readingLists.value.keys
        )
        assertEquals(listOf("m1"), vm.readingLists.value["reading"]?.map { it.mangaId })
        assertEquals(2, vm.achievementsUnlocked.value)
        }
    }

    @Test
    fun userProfile_markReadAndPrivacyForwardToRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        stubUserProfileVm()
        val vm = createUserProfileVm()
        advanceUntilIdle()
        vm.markRead("n1")
        vm.updatePrivacy(showListsPublic = true, showActivityPublic = false)
        advanceUntilIdle()
        coVerify { communityRepo.markNotificationRead("n1") }
        coVerify { communityRepo.updateProfilePrivacy(true, false) }
        }
    }

    // ─── UserListsViewModel ──────────────────────────────────────────────────

    @Test
    fun userLists_saveListCreatesAndSelectsNewList() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { communityRepo.observeUserLists() } returns flowOf(listOf(testList()))
        every { communityRepo.observeListItems(any()) } returns flowOf(emptyList())
        coEvery {
            communityRepo.createOrUpdateList(null, "Summer", "desc", "", 4.5f, listOf("Action"), true)
        } returns "new-id"
        val vm = UserListsViewModel(communityRepo, cloudinaryUploader)
        advanceUntilIdle()
        vm.saveList(null, "Summer", "desc", "", 4.5f, listOf("Action"), true)
        advanceUntilIdle()
        assertEquals("new-id", vm.selectedListId.value)
        coVerify {
            communityRepo.createOrUpdateList(null, "Summer", "desc", "", 4.5f, listOf("Action"), true)
        }
        }
    }

    @Test
    fun userLists_deleteListForwardsToRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { communityRepo.observeUserLists() } returns flowOf(listOf(testList()))
        every { communityRepo.observeListItems(any()) } returns flowOf(emptyList())
        val vm = UserListsViewModel(communityRepo, cloudinaryUploader)
        advanceUntilIdle()
        vm.deleteList("l1")
        advanceUntilIdle()
        coVerify { communityRepo.deleteList("l1") }
        }
    }

    // ─── PublicProfileViewModel ──────────────────────────────────────────────

    @Test
    fun publicProfile_privateProfileHidesListsAndActivity() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { sessionManager.currentUserId() } returns "u1"
        every { communityRepo.observePublicProfile(any()) } returns flowOf(
            testProfile(uid = "u2", username = "someone").copy(
                showListsPublic = false,
                showActivityPublic = false,
                showLibraryPublic = false
            )
        )
        every { communityRepo.observePublicLists(any()) } returns flowOf(listOf(testList()))
        every { communityRepo.observePublicActivity(any()) } returns flowOf(listOf(testComment()))
        every { communityRepo.observePublicListItems(any(), any()) } returns flowOf(emptyList())
        val vm = PublicProfileViewModel(
            SavedStateHandle(mapOf("userId" to "u2")),
            communityRepo,
            libraryRepo,
            sessionManager
        )
        advanceUntilIdle()
        assertFalse(vm.isOwnProfile)
        assertEquals("u2", vm.state.value.profile?.uid)
        assertTrue(vm.state.value.lists.isEmpty())
        assertTrue(vm.state.value.activity.isEmpty())
        assertTrue(vm.state.value.readingLists.isEmpty())
        }
    }

    @Test
    fun publicProfile_toggleFollowFlipsState() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { sessionManager.currentUserId() } returns "u1"
        every { communityRepo.observePublicProfile(any()) } returns flowOf(testProfile(uid = "u2"))
        every { communityRepo.observePublicLists(any()) } returns flowOf(emptyList())
        every { communityRepo.observePublicActivity(any()) } returns flowOf(emptyList())
        every { communityRepo.observePublicListItems(any(), any()) } returns flowOf(emptyList())
        val vm = PublicProfileViewModel(
            SavedStateHandle(mapOf("userId" to "u2")),
            communityRepo,
            libraryRepo,
            sessionManager
        )
        advanceUntilIdle()
        assertFalse(vm.isFollowing.value)
        vm.toggleFollow()
        assertTrue(vm.isFollowing.value)
        vm.toggleFollow()
        assertFalse(vm.isFollowing.value)
        }
    }

    // ─── ProfileSettingsViewModel ────────────────────────────────────────────

    @Test
    fun profileSettings_blankUsernameFallsBackToCurrent() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        stubProfileSettingsVm()
        val vm = createProfileSettingsVm()
        advanceUntilIdle()
        vm.updateProfile("", "New bio", "")
        advanceUntilIdle()
        // Blank username/displayName must not wipe the stored identity.
        coVerify {
            communityRepo.upsertProfile(
                username = "old",
                bio = "New bio",
                isPublic = true,
                avatarUrl = null,
                bannerUrl = null,
                displayName = ""
            )
        }
        }
    }

    @Test
    fun profileSettings_setFavoriteGenresUpdatesStateAndPersists() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        stubProfileSettingsVm()
        val vm = createProfileSettingsVm()
        advanceUntilIdle()
        vm.setFavoriteGenres(listOf("Action", "Drama"))
        assertEquals(listOf("Action", "Drama"), vm.favoriteGenres.value)
        advanceUntilIdle()
        coVerify { settingsRepo.setFavoriteGenres(listOf("Action", "Drama")) }
        }
    }

    // ─── CollectionsViewModel ────────────────────────────────────────────────

    @Test
    fun collections_exposesFlowAndCreateForwards() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { collectionManager.collections } returns flowOf(listOf(testCollection()))
        coEvery { collectionManager.createCollection(any(), any()) } returns testCollection(id = "new")
        val vm = CollectionsViewModel(collectionManager)
        vm.createCollection("New", "Desc")
        vm.collections.test {
            advanceUntilIdle()
            val final = expectMostRecentItem()
            assertEquals(listOf("c1"), final.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        coVerify { collectionManager.createCollection("New", "Desc") }
        }
    }

    // ─── CollectionDetailViewModel ───────────────────────────────────────────

    @Test
    fun collectionDetail_loadCollectionResolvesById() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { collectionManager.collections } returns flowOf(
            listOf(testCollection(id = "c1"), testCollection(id = "c2"))
        )
        coEvery { mangaCacheDao.get("azora_slug1") } returns MangaCacheEntity(
            mangaId = "azora_slug1",
            slug = "slug1",
            title = "Cached Title",
            coverUrl = "https://example.com/cover.jpg",
            sourceId = "azora"
        )
        val vm = CollectionDetailViewModel(collectionManager, mangaCacheDao)
        vm.loadCollection("c1")
        advanceUntilIdle()
        assertEquals("c1", vm.collection.value?.id)
        assertEquals(listOf("azora_slug1"), vm.collection.value?.mangaIds)
        assertEquals("Cached Title", vm.getCachedManga("azora_slug1")?.title)
        }
    }

    @Test
    fun collectionDetail_removeMangaForwardsToManager() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { collectionManager.collections } returns flowOf(emptyList())
        val vm = CollectionDetailViewModel(collectionManager, mangaCacheDao)
        vm.removeMangaFromCollection("c1", "azora_slug1")
        advanceUntilIdle()
        coVerify { collectionManager.removeMangaFromCollection("c1", "azora_slug1") }
        }
    }

    // ─── GoalsViewModel ──────────────────────────────────────────────────────

    @Test
    fun goals_createAndDeleteForwardToManager() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { achievementManager.goals } returns flowOf(emptyList())
        every { achievementManager.achievements } returns flowOf(emptyList())
        every { achievementManager.totalPagesRead } returns flowOf(100)
        every { achievementManager.totalChaptersRead } returns flowOf(5)
        val vm = GoalsViewModel(achievementManager)
        vm.createGoal(GoalType.PAGES_READ, 50, GoalPeriod.DAILY)
        vm.deleteGoal("g1")
        advanceUntilIdle()
        coVerify { achievementManager.createGoal(GoalType.PAGES_READ, 50, GoalPeriod.DAILY) }
        coVerify { achievementManager.deleteGoal("g1") }
        }
    }

    @Test
    fun goals_progressPercentComputation() {
        // Pure ReadingGoal math behind the GoalCard progress bar: no VM needed.
        val quarter = ReadingGoal(id = "g1", type = GoalType.PAGES_READ, targetValue = 100, currentValue = 25)
        assertEquals(0.25f, quarter.progressPercent)
        assertFalse(quarter.isCompleted)
        val done = quarter.copy(currentValue = 100)
        assertEquals(1f, done.progressPercent)
        assertTrue(done.isCompleted)
        val overflow = quarter.copy(currentValue = 150)
        assertEquals(1f, overflow.progressPercent)
        // Zero target must not divide by zero (dialog validation is UI-side).
        val zeroTarget = quarter.copy(targetValue = 0, currentValue = 10)
        assertEquals(0f, zeroTarget.progressPercent)
    }

    // ─── MoreViewModel ───────────────────────────────────────────────────────

    @Test
    fun more_roleDefaultsToViewerAndExposesModerator() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { communityRepo.getCurrentProfile() } returns testProfile().copy(role = "moderator")
        val moderatorVm = MoreViewModel(communityRepo)
        advanceUntilIdle()
        assertEquals("moderator", moderatorVm.role.value)
        coEvery { communityRepo.getCurrentProfile() } returns null
        val guestVm = MoreViewModel(communityRepo)
        advanceUntilIdle()
        assertEquals("viewer", guestVm.role.value)
        }
    }

    // ─── test data ───────────────────────────────────────────────────────────

    private fun testProfile(uid: String = "u1", username: String = "tester") = CommunityProfile(
        uid = uid,
        username = username
    )

    private fun testFavorite(id: String) = FavoriteManga(
        mangaId = id,
        slug = "slug-$id",
        title = "Manga $id",
        coverUrl = "https://example.com/cover.jpg",
        source = MangaSource.AZORA
    )

    private fun testList() = CustomUserList(
        id = "l1",
        name = "Summer reads",
        description = "desc",
        itemCount = 0
    )

    private fun testComment() = CommunityComment(
        id = "c1",
        mangaId = "m1",
        authorUid = "u2",
        authorName = "Sam",
        text = "Nice chapter"
    )

    private fun testCollection(id: String = "c1") = MangaCollection(
        id = id,
        name = "Collection $id",
        description = "desc",
        mangaIds = listOf("azora_slug1")
    )
}
