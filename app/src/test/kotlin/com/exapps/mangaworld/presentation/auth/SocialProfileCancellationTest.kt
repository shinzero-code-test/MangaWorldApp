package com.exapps.mangaworld.presentation.auth

import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * U-13 (presentation lane): a cancelled call must NOT be swallowed by the
 * `runCatching` guards inside [ensureSocialProfile].
 *
 * `runCatching` catches [CancellationException], so a cancelled profile read
 * used to degrade into "no profile" and the caller then performed the
 * post-cancel write anyway — work continuing after the user navigated away.
 * `suspendRunCatching` rethrows cancellation, so:
 *
 *  - a cancelled READ must propagate and must NOT trigger a write, and
 *  - a cancelled WRITE must propagate immediately instead of being retried
 *    three times as if it were an ordinary failure.
 *
 * Ordinary failures must still be captured (the function returns false) — this
 * test also guards against over-correcting into "never swallow anything".
 */
class SocialProfileCancellationTest {

    private fun firebaseUser(): FirebaseUser {
        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.email } returns "Ahmed.Mohamed+test@gmail.com"
        every { user.displayName } returns "Ahmed Mohamed"
        every { user.uid } returns "AbC123xYz"
        return user
    }

    @Test
    fun cancelledProfileRead_propagatesAndSkipsTheProfileWrite() = runTest {
        val repo = mockk<CommunityRepository>()
        coEvery { repo.getCurrentProfile() } throws CancellationException("cancelled")

        try {
            repo.ensureSocialProfile(firebaseUser(), "u")
            fail("CancellationException must propagate out of ensureSocialProfile")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        // Post-cancel state write must NOT run.
        coVerify(exactly = 0) {
            repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun cancelledProfileWrite_propagatesWithoutARetryStorm() = runTest {
        val repo = mockk<CommunityRepository>()
        coEvery { repo.getCurrentProfile() } returns null
        coEvery {
            repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        try {
            repo.ensureSocialProfile(firebaseUser(), "u")
            fail("CancellationException must propagate out of ensureSocialProfile")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        // Exactly one attempt: cancellation is not a "username taken" signal.
        coVerify(exactly = 1) {
            repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun ordinaryFailure_isStillCapturedAndReportedAsFalse() = runTest {
        val repo = mockk<CommunityRepository>()
        coEvery { repo.getCurrentProfile() } returns null
        coEvery {
            repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("down")

        // A real failure degrades to false — it must NOT propagate.
        assertFalse(repo.ensureSocialProfile(firebaseUser(), "u"))
    }
}
