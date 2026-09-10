package com.exapps.mangaworld.viewmodel

import android.content.Context
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.firebase.AccountMergeReason
import com.exapps.mangaworld.core.firebase.AccountMergeRequiredException
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.UsernameRules
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.auth.LoginViewModel
import com.exapps.mangaworld.presentation.utils.filterLatestUpdates
import com.exapps.mangaworld.presentation.utils.formatDiagnosticBytes
import com.exapps.mangaworld.presentation.utils.normalizeBlacklistInput
import com.exapps.mangaworld.presentation.utils.shouldTriggerSmartPrefetch
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginUtilsViewModelTest {

    private data class Harness(
        val context: Context,
        val sessionManager: FirebaseSessionManager,
        val communityRepo: CommunityRepository,
        val vm: LoginViewModel
    )

    // Fresh mocks + dispatcher per test: coEvery stubs from one test must not
    // leak into the next, and init collectors stay parked on their own scheduler.
    private fun newHarness(dispatcher: kotlinx.coroutines.test.TestDispatcher): Harness {
        Dispatchers.setMain(dispatcher)
        val context = mockk<Context>(relaxed = true)
        val strings = mapOf(
            R.string.auth_error_empty_fields to "E-empty",
            R.string.auth_error_login_failed to "E-login-failed",
            R.string.auth_error_signup_failed to "E-signup-failed",
            R.string.auth_error_display_name_required to "E-display-name",
            R.string.enter_username to "E-username-required",
            R.string.str_108 to "E-username-rules",
            R.string.enter_email to "E-enter-email",
            R.string.error_retry to "E-retry",
            R.string.str_433 to "S-merge-email"
        )
        strings.forEach { (resId, value) -> every { context.getString(resId) } returns value }
        val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
        every { sessionManager.authState } returns flowOf(null)
        val communityRepo = mockk<CommunityRepository>(relaxed = true)
        val vm = LoginViewModel(context, sessionManager, communityRepo)
        return Harness(context, sessionManager, communityRepo, vm)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun signIn_blankEmail_setsEmptyFieldsError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.signInWithEmail("", "password123")
            advanceUntilIdle()
            assertEquals("E-empty", h.vm.uiState.value.error)
            coVerify(exactly = 0) { h.sessionManager.signInWithEmail(any(), any()) }
        }
    }

    @Test
    fun signIn_blankPassword_setsEmptyFieldsError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.signInWithEmail("user@example.com", "   ")
            advanceUntilIdle()
            assertEquals("E-empty", h.vm.uiState.value.error)
            coVerify(exactly = 0) { h.sessionManager.signInWithEmail(any(), any()) }
        }
    }

    @Test
    fun signIn_trimsEmailBeforeBackendCall() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            coEvery { h.sessionManager.signInWithEmail(any(), any()) } returns "uid1"
            h.vm.signInWithEmail("  user@example.com  ", "pw123456")
            advanceUntilIdle()
            coVerify(exactly = 1) { h.sessionManager.signInWithEmail("user@example.com", "pw123456") }
            assertEquals("user@example.com", h.vm.uiState.value.email)
        }
    }

    @Test
    fun signIn_success_setsSignedInAndClearsLoading() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            coEvery { h.sessionManager.signInWithEmail(any(), any()) } returns "uid1"
            h.vm.signInWithEmail("user@example.com", "pw123456")
            advanceUntilIdle()
            val state = h.vm.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.isSignedIn)
            assertNull(state.error)
        }
    }

    @Test
    fun signIn_nullUid_setsGenericLoginFailedError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            coEvery { h.sessionManager.signInWithEmail(any(), any()) } returns null
            h.vm.signInWithEmail("user@example.com", "pw123456")
            advanceUntilIdle()
            val state = h.vm.uiState.value
            assertFalse(state.isLoading)
            assertFalse(state.isSignedIn)
            assertEquals("E-login-failed", state.error)
        }
    }

    @Test
    fun signIn_exception_neverLeaksRawText() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            coEvery { h.sessionManager.signInWithEmail(any(), any()) } throws
                RuntimeException("super-secret-backend-boom")
            h.vm.signInWithEmail("user@example.com", "pw123456")
            advanceUntilIdle()
            val state = h.vm.uiState.value
            assertFalse(state.isLoading)
            assertFalse(state.isSignedIn)
            // Generic mapped message (error_retry sentinel), never the raw text.
            assertEquals("E-retry", state.error)
            assertFalse(state.error!!.contains("boom"))
        }
    }

    @Test
    fun signIn_mergeException_showsMergeGuidance() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            val mergeEx = mockk<AccountMergeRequiredException>()
            every { mergeEx.reason } returns AccountMergeReason.EMAIL_ALREADY_IN_USE
            coEvery { h.sessionManager.signInWithEmail(any(), any()) } throws mergeEx
            h.vm.signInWithEmail("user@example.com", "pw123456")
            advanceUntilIdle()
            val state = h.vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals("S-merge-email", state.error)
        }
    }

    @Test
    fun signUp_blankDisplayName_setsDisplayNameError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.signUpWithEmail("user@example.com", "pw123456", "", "valid_name")
            advanceUntilIdle()
            assertEquals("E-display-name", h.vm.uiState.value.error)
            coVerify(exactly = 0) { h.sessionManager.signUpWithEmail(any(), any(), any(), any()) }
        }
    }

    @Test
    fun signUp_shortUsername_setsUsernameRulesError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.signUpWithEmail("user@example.com", "pw123456", "Display", "ab")
            advanceUntilIdle()
            assertEquals("E-username-rules", h.vm.uiState.value.error)
            coVerify(exactly = 0) { h.sessionManager.signUpWithEmail(any(), any(), any(), any()) }
        }
    }

    @Test
    fun fieldChanged_trimsEmailAndClearsError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.signInWithEmail("", "pw123456")
            advanceUntilIdle()
            assertEquals("E-empty", h.vm.uiState.value.error)
            h.vm.onEmailChanged("  user@example.com  ")
            assertEquals("user@example.com", h.vm.uiState.value.email)
            assertNull(h.vm.uiState.value.error)
            h.vm.clearError()
            assertNull(h.vm.uiState.value.error)
        }
    }

    @Test
    fun sendPasswordReset_blankEmail_setsEnterEmailError() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val h = newHarness(dispatcher)
            h.vm.sendPasswordReset("   ")
            advanceUntilIdle()
            assertEquals("E-enter-email", h.vm.uiState.value.error)
            assertFalse(h.vm.uiState.value.passwordResetSent)
            coVerify(exactly = 0) { h.sessionManager.sendPasswordResetEmail(any()) }
        }
    }

    @Test
    fun usernameRules_boundariesAndUnicode() {
        // 2 chars: below minimum. 21 chars: above maximum.
        assertFalse(UsernameRules.isValid("ab"))
        assertFalse(UsernameRules.isValid("a".repeat(21)))
        // Exact boundaries pass.
        assertTrue(UsernameRules.isValid("abc"))
        assertTrue(UsernameRules.isValid("a".repeat(20)))
        // Leading/trailing underscores rejected by REGEX.
        assertFalse(UsernameRules.isValid("_abc"))
        assertFalse(UsernameRules.isValid("abc_"))
        assertTrue(UsernameRules.isValid("user_name1"))
        // Non-ASCII rejected: LoginViewModel trims+lowercases but never transliterates.
        assertFalse(UsernameRules.isValid("مستخدم"))
        assertFalse(UsernameRules.isValid(""))
        assertFalse(UsernameRules.isValid("   "))
    }

    @Test
    fun presentationUtils_edgeCases() {
        // Unicode blacklist lines: kept, trimmed, deduplicated; blanks dropped.
        assertEquals(
            setOf("سولو"),
            normalizeBlacklistInput("  سولو\nسولو\n\n\t ")
        )
        // Empty update list stays empty under any filter flags.
        assertTrue(filterLatestUpdates(emptyList(), null, true, emptyMap()).isEmpty())
        // unreadOnly=false keeps read items across sources.
        val olympus = LatestChapterItem(
            mangaId = "olympus_solo",
            mangaSlug = "solo-leveling",
            mangaTitle = "Solo Leveling",
            coverUrl = "",
            chapterNumber = 1f,
            chapterUrl = "https://olympustaff.example/series/solo-leveling/1",
            timeAgo = "1h",
            source = MangaSource.OLYMPUS
        )
        val starz = olympus.copy(mangaId = "starz_solo", source = MangaSource.STARZ)
        val readStates = mapOf(olympus.chapterUrl to true, starz.chapterUrl to true)
        assertEquals(2, filterLatestUpdates(listOf(olympus, starz), null, false, readStates).size)
        // Prefetch: overflow counts as past-halfway; negative never triggers;
        // odd totals round down (3/2=1, so page 1 triggers).
        assertTrue(shouldTriggerSmartPrefetch(currentPage = 11, totalPages = 10))
        assertFalse(shouldTriggerSmartPrefetch(currentPage = -1, totalPages = 10))
        assertTrue(shouldTriggerSmartPrefetch(currentPage = 1, totalPages = 3))
        // Byte formatting: exact MiB boundary and %.0f rounding.
        assertEquals("1.0 MB", formatDiagnosticBytes(1024L * 1024L))
        assertEquals("2 KB", formatDiagnosticBytes(1536L))
    }
}
