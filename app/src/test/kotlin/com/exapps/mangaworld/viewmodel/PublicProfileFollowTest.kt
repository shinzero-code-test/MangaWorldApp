package com.exapps.mangaworld.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.LibraryRepository
import com.exapps.mangaworld.domain.repository.PublicLibraryState
import com.exapps.mangaworld.presentation.profile.PublicProfileViewModel
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Follow button truthfulness: the button renders the observed
 * relationships/{me}/following/{them} state (never a local flip), and the
 * toggle writes through the repository. A failed write snaps back by itself
 * because the observed flow never changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicProfileFollowTest {

    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val libraryRepo = mockk<LibraryRepository>(relaxed = true)
    private val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)

    @Before
    fun setup() {
        clearMocks(communityRepo, libraryRepo, sessionManager)
        every { sessionManager.currentUserId() } returns "me"
        every { communityRepo.isFollowing(any()) } returns flowOf(false)
        every { communityRepo.observePublicProfile(any()) } returns flowOf(null)
        every { communityRepo.observePublicLists(any()) } returns flowOf(emptyList())
        every { communityRepo.observePublicActivity(any()) } returns flowOf(emptyList())
        every { communityRepo.observePublicListItems(any(), any()) } returns flowOf(emptyList())
        every { communityRepo.observePublicLibrary(any()) } returns flowOf(PublicLibraryState.Ready(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(userId: String = "them") = PublicProfileViewModel(
        savedStateHandle = SavedStateHandle(mapOf("userId" to userId)),
        communityRepository = communityRepo,
        libraryRepository = libraryRepo,
        sessionManager = sessionManager
    )

    @Test
    fun followButton_reflectsObservedState() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            every { communityRepo.isFollowing("them") } returns flowOf(true)
            val vm = newVm()
            advanceUntilIdle()
            assertTrue(vm.isFollowing.value)
        }
    }

    @Test
    fun toggleFollow_whenNotFollowing_writesFollow() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = newVm()
            advanceUntilIdle()
            assertFalse(vm.isFollowing.value)
            vm.toggleFollow()
            advanceUntilIdle()
            coVerify(exactly = 1) { communityRepo.followUser("them") }
            coVerify(exactly = 0) { communityRepo.unfollowUser(any()) }
        }
    }

    @Test
    fun toggleFollow_whenFollowing_writesUnfollow() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            every { communityRepo.isFollowing("them") } returns flowOf(true)
            val vm = newVm()
            advanceUntilIdle()
            vm.toggleFollow()
            advanceUntilIdle()
            coVerify(exactly = 1) { communityRepo.unfollowUser("them") }
            coVerify(exactly = 0) { communityRepo.followUser(any()) }
        }
    }

    @Test
    fun toggleFollow_ownProfile_isNoop() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = newVm(userId = "me")
            advanceUntilIdle()
            assertTrue(vm.isOwnProfile)
            vm.toggleFollow()
            advanceUntilIdle()
            coVerify(exactly = 0) { communityRepo.followUser(any()) }
            coVerify(exactly = 0) { communityRepo.unfollowUser(any()) }
        }
    }
}
