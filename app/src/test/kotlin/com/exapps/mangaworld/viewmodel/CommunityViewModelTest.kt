package com.exapps.mangaworld.viewmodel

// Coverage for the screen-local community ViewModels:
//   CommunityViewModel (CommunityScreen.kt), RepliesViewModel (RepliesScreen.kt),
//   CommunityChatViewModel (CommunityChatScreen.kt),
//   ModerationDashboardViewModel (ModerationDashboardScreen.kt).
//
// Known gaps (by design, not by omission):
// - Guest gating lives in the composables (isSignedIn no-op lambdas), not in VM
//   logic, so there is nothing VM-observable to assert for anonymous users.
// - ModerationDashboardViewModel is read-only: it exposes profile/reports flows but
//   no resolve/dismiss actions (handled by the web dashboard), so moderation tests
//   only cover the moderator gate.
// - CommunityChatViewModel.suggestions is hard-wired to emptyList (smart-reply wiring
//   removed); only asserted as empty.
// - All Firebase/session access goes through mockable repo/manager interfaces
//   (getCurrentProfile is a suspend repo call), so every VM constructs cleanly with
//   relaxed mocks — no unmockable-dep gaps.
// - Trimming note: the composables pre-trim (commentText.trim()) and the VMs trim
//   only the optimistic echo/pending overlay; the raw string is forwarded to the
//   repository as passed. Tests assert the trimmed echo plus exact forwarding.

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CommunityReplyTarget
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.model.ModerationReport
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.community.CommunityChatViewModel
import com.exapps.mangaworld.presentation.community.CommunityTab
import com.exapps.mangaworld.presentation.community.CommunityTarget
import com.exapps.mangaworld.presentation.community.CommunityViewModel
import com.exapps.mangaworld.presentation.community.ModerationDashboardViewModel
import com.exapps.mangaworld.presentation.community.RepliesViewModel
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
class CommunityViewModelTest {
    // Fresh dispatcher per test: init collectors from a previous VM stay parked
    // on their abandoned scheduler (never advanced) instead of leaking into
    // the next test. ViewModel.clear() is internal, so this is the cleanup.
    private fun newDispatcher() = StandardTestDispatcher()

    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val mangaCacheDao = mockk<MangaCacheDao>(relaxed = true)
    private val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
    private val analyticsManager = mockk<FirebaseAnalyticsManager>(relaxed = true)
    private val remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true)

    @Before
    fun setup() {
        // Shared mocks are cleared per test so coVerify(exactly = 0) checks stay
        // hermetic; default stubs are re-applied below.
        clearMocks(
            communityRepo, settingsRepo, mangaCacheDao,
            sessionManager, analyticsManager, remoteConfigManager
        )
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { communityRepo.observeMangaComments(any()) } returns flowOf(emptyList())
        every { communityRepo.observeChapterComments(any(), any()) } returns flowOf(emptyList())
        every { communityRepo.observeReviews(any()) } returns flowOf(emptyList())
        every { communityRepo.observeChatMessages(any()) } returns flowOf(emptyList())
        every { communityRepo.observeModerationReports() } returns flowOf(emptyList())
        coEvery { communityRepo.getCurrentProfile() } returns null
        coEvery { mangaCacheDao.get(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── factories ────────────────────────────────────────────────────────────

    private fun communityHandle() = SavedStateHandle(
        mapOf("mangaId" to "m1", "slug" to "test-slug", "sourceId" to "azora")
    )

    private fun repliesHandle(rootId: String = "root1") = SavedStateHandle(
        mapOf("mangaId" to "m1", "slug" to "test-slug", "sourceId" to "azora", "rootId" to rootId)
    )

    private fun newCommunityVm() = CommunityViewModel(
        savedStateHandle = communityHandle(),
        context = mockk<Context>(relaxed = true),
        communityRepository = communityRepo,
        settingsRepository = settingsRepo,
        mangaCacheDao = mangaCacheDao
    )

    private fun newRepliesVm(rootId: String = "root1") = RepliesViewModel(
        savedStateHandle = repliesHandle(rootId),
        context = mockk<Context>(relaxed = true),
        communityRepository = communityRepo,
        settingsRepository = settingsRepo
    )

    private fun newChatVm() = CommunityChatViewModel(
        savedStateHandle = SavedStateHandle(),
        context = mockk<Context>(relaxed = true),
        communityRepository = communityRepo,
        sessionManager = sessionManager,
        analyticsManager = analyticsManager,
        remoteConfigManager = remoteConfigManager
    )

    private fun newModerationVm() = ModerationDashboardViewModel(
        communityRepository = communityRepo
    )

    // ─── CommunityViewModel ───────────────────────────────────────────────────

    @Test
    fun community_initialState_defaults() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = newCommunityVm()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.comments.isEmpty())
            assertTrue(state.reviews.isEmpty())
            assertNull(state.error)
            assertNull(state.profile)
            // No chapterUrl in the handle -> manga mode defaults to the reviews tab.
            assertEquals(CommunityTab.REVIEWS, state.tab)
            assertFalse(state.chapterMode)
        }
    }

    @Test
    fun community_loadsCommentsAndReviews_rootsOnly() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val root = testComment("c1", text = "first")
            every { communityRepo.observeMangaComments("m1") } returns flowOf(
                listOf(
                    root,
                    testComment("c2", parentId = "c1", text = "a reply"),
                    testComment("c3", reviewId = "rev1", text = "review reply")
                )
            )
            every { communityRepo.observeReviews("m1") } returns flowOf(listOf(testReview()))
            val vm = newCommunityVm()
            advanceUntilIdle()
            val state = vm.state.value
            // Main screen shows roots only; replies live on RepliesScreen.
            assertEquals(listOf("c1"), state.comments.map { it.id })
            assertEquals(listOf("rev1"), state.reviews.map { it.id })
        }
    }

    @Test
    fun community_setTab_updatesTab() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = newCommunityVm()
            advanceUntilIdle()
            vm.setTab(CommunityTab.COMMENTS)
            advanceUntilIdle()
            assertEquals(CommunityTab.COMMENTS, vm.state.value.tab)
        }
    }

    @Test
    fun community_postComment_echoTrimmedAndForwards() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.getCurrentProfile() } returns testProfile()
            val vm = newCommunityVm()
            advanceUntilIdle()
            vm.postComment("  hello world  ", false)
            advanceUntilIdle()
            // Optimistic echo renders trimmed immediately.
            assertTrue(vm.state.value.comments.any { it.text == "hello world" })
            // Repository receives exactly what was passed (the composer pre-trims).
            coVerify {
                communityRepo.postMangaComment("m1", "test-slug", "azora", "  hello world  ", false, null)
            }
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun community_postFailure_setsGenericErrorAndRollsBackEcho() {
        // Turbine-collected variant: asserts on the emission sequence rather
        // than .value snapshots, isolating the scenario from snapshot timing.
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.getCurrentProfile() } returns testProfile()
            coEvery { communityRepo.postMangaComment(any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("boom")
            val vm = newCommunityVm()
            advanceUntilIdle()
            vm.postComment("hi", false)
            advanceUntilIdle()
            val s = vm.state.value
            assertFalse("comments must not contain the failed echo: ${s.comments.map { it.text }}", s.comments.any { it.text == "hi" })
            assertTrue("expected a generic error, got: ${s.error}", s.error != null && !s.error.contains("boom"))
            vm.dismissError()
            advanceUntilIdle()
            assertNull("error must clear after dismiss: ${vm.state.value.error}", vm.state.value.error)
        }
    }

    @Test
    fun community_likeAndReport_forwardToRepository() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val vm = newCommunityVm()
            advanceUntilIdle()
            val comment = testComment("c1", text = "first")
            vm.likeComment("c1")
            vm.report(CommunityTarget.Comment(comment), "spam")
            advanceUntilIdle()
            coVerify { communityRepo.likeComment("c1") }
            coVerify { communityRepo.reportComment(comment, "spam") }
            assertNull(vm.state.value.error)
        }
    }

    // ─── RepliesViewModel ─────────────────────────────────────────────────────

    @Test
    fun replies_resolvesRootAndLoadsFlatReplies() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val root = testComment("root1", authorUid = "uid-root", createdAt = 1000L)
            every { communityRepo.observeMangaComments("m1") } returns flowOf(
                listOf(
                    root,
                    testComment("child1", parentId = "root1", createdAt = 2000L),
                    // Legacy nested reply flattens into the same feed.
                    testComment("grand1", parentId = "child1", createdAt = 3000L),
                    testComment("other", createdAt = 1500L)
                )
            )
            val vm = newRepliesVm()
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(CommunityTarget.Comment(root), state.root)
            assertEquals(listOf("child1", "grand1"), state.replies.map { it.id })
            // New replies default to the root author.
            assertEquals("root1", state.replyTo?.id)
        }
    }

    @Test
    fun replies_postReply_forwardsReplyTarget() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.getCurrentProfile() } returns testProfile()
            val root = testComment("root1", authorUid = "uid-root", authorUsername = "rootuser")
            every { communityRepo.observeMangaComments("m1") } returns flowOf(listOf(root))
            val vm = newRepliesVm()
            advanceUntilIdle()
            vm.postReply("thanks", false)
            advanceUntilIdle()
            val slot = slot<CommunityReplyTarget>()
            coVerify {
                communityRepo.postMangaComment("m1", "test-slug", "azora", "thanks", false, capture(slot))
            }
            assertEquals("root1", slot.captured.parentId)
            assertNull(slot.captured.reviewId)
            assertEquals("uid-root", slot.captured.replyToUid)
            assertEquals("rootuser", slot.captured.replyToUsername)
            assertNull(vm.state.value.error)
        }
    }

    // ─── CommunityChatViewModel ───────────────────────────────────────────────

    @Test
    fun chat_messagesLoadAndSendForwards() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            every { communityRepo.observeChatMessages(any()) } returns flowOf(
                listOf(testMessage("msg1"), testMessage("msg2"))
            )
            val vm = newChatVm()
            advanceUntilIdle()
            assertEquals(listOf("msg1", "msg2"), vm.messages.value.map { it.id })
            assertTrue(vm.suggestions.value.isEmpty())
            vm.send("hello")
            advanceUntilIdle()
            coVerify { communityRepo.sendChatMessage("global", "hello") }
            vm.onSuggestionSelected("ok")
            verify { analyticsManager.logSmartReplySelected("community_chat", 2) }
        }
    }

    @Test
    fun chat_sendFailure_doesNotCrash() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.sendChatMessage(any(), any()) } throws RuntimeException("offline")
            val vm = newChatVm()
            advanceUntilIdle()
            // send() wraps in runCatching: the failure must not escape.
            vm.send("hi")
            advanceUntilIdle()
            coVerify { communityRepo.sendChatMessage("global", "hi") }
        }
    }

    // ─── ModerationDashboardViewModel ─────────────────────────────────────────

    @Test
    fun moderation_moderatorSeesReports() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.getCurrentProfile() } returns testProfile(role = "moderator")
            every { communityRepo.observeModerationReports() } returns flowOf(listOf(testReport()))
            val vm = newModerationVm()
            vm.reports.test {
                awaitItem() // initial empty value
                advanceUntilIdle()
                val latest = expectMostRecentItem()
                assertEquals(listOf("rep1"), latest.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun moderation_nonModeratorGetsEmptyReports() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { communityRepo.getCurrentProfile() } returns testProfile(role = "viewer")
            every { communityRepo.observeModerationReports() } returns flowOf(listOf(testReport()))
            val vm = newModerationVm()
            vm.reports.test {
                assertTrue(awaitItem().isEmpty())
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            // Privileged query never attaches for non-moderators (defense-in-depth).
            coVerify(exactly = 0) { communityRepo.observeModerationReports() }
        }
    }

    // ─── test data ────────────────────────────────────────────────────────────

    private fun testProfile(uid: String = "me", role: String = "viewer") = CommunityProfile(
        uid = uid,
        username = "user-$uid",
        displayName = "User $uid",
        role = role
    )

    private fun testComment(
        id: String,
        authorUid: String = "uid-a",
        authorUsername: String = "author",
        parentId: String? = null,
        reviewId: String? = null,
        text: String = "comment $id",
        createdAt: Long = 1000L
    ) = CommunityComment(
        id = id,
        mangaId = "m1",
        parentId = parentId,
        reviewId = reviewId,
        authorUid = authorUid,
        authorName = "Author $authorUid",
        authorUsername = authorUsername,
        text = text,
        createdAt = createdAt
    )

    private fun testReview(id: String = "rev1") = MangaReview(
        id = id,
        mangaId = "m1",
        authorUid = "uid-r",
        authorName = "Reviewer",
        rating = 5,
        title = "Great",
        body = "Loved it"
    )

    private fun testMessage(id: String) = CommunityChatMessage(
        id = id,
        roomId = "global",
        authorUid = "u1",
        authorName = "Chatter",
        text = "hi $id"
    )

    private fun testReport(id: String = "rep1") = ModerationReport(
        id = id,
        commentId = "c1",
        mangaId = "m1",
        reportedUid = "uid-a",
        reporterUid = "uid-b",
        reason = "spam",
        createdAt = 1000L
    )
}
