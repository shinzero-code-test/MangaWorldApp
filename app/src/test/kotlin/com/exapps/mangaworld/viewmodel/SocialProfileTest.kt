package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.auth.ensureSocialProfile
import com.exapps.mangaworld.presentation.auth.usernameFromEmail
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Social-signup provisioning: display name = provider account name, username =
 * sanitized email local-part. Guards the "new social user looks like a guest"
 * regression class.
 */
class SocialProfileTest {

    private fun firebaseUser(email: String? = "Ahmed.Mohamed+test@gmail.com", name: String? = "Ahmed Mohamed"): FirebaseUser {
        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.email } returns email
        every { user.displayName } returns name
        every { user.uid } returns "AbC123xYz"
        return user
    }

    // ─── usernameFromEmail ────────────────────────────────────────────────

    @Test
    fun usernameFromEmail_sanitizesLocalPart() {
        assertEquals("ahmed_mohamed_test", usernameFromEmail("Ahmed.Mohamed+test@gmail.com", "uid123"))
    }

    @Test
    fun usernameFromEmail_collapsesSeparatorsAndTruncates() {
        assertEquals("a_very_long_email_ad", usernameFromEmail("a.very.long.email.address.here@example.com", "uid123"))
    }

    @Test
    fun usernameFromEmail_fallsBackToUidWhenTooShort() {
        assertEquals("user_yz1234", usernameFromEmail("ab@x.io", "AAAbBBxYz1234"))
        assertEquals("user_yz1234", usernameFromEmail(null, "AAAbBBxYz1234"))
        assertEquals("user_yz1234", usernameFromEmail("@@@ab@example.com", "AAAbBBxYz1234"))
        // Exactly 3 chars after sanitizing stays as-is.
        assertEquals("loh", usernameFromEmail("@@@loh@example.com", "AAAbBBxYz1234"))
    }

    @Test
    fun usernameFromEmail_resultsAlwaysPassRules() {
        listOf(
            usernameFromEmail("Ahmed.Mohamed+test@gmail.com", "u1"),
            usernameFromEmail("a@b.co", "u2"),
            usernameFromEmail(null, "u3"),
            usernameFromEmail("محمد@example.com", "u4"),
            usernameFromEmail("___@example.com", "u5"),
            usernameFromEmail("a.very.long.email.address.here@example.com", "u6")
        ).forEach { candidate ->
            assertTrue(
                "must be rule-valid: $candidate",
                com.exapps.mangaworld.domain.UsernameRules.isValid(candidate)
            )
        }
    }

    // ─── ensureSocialProfile ──────────────────────────────────────────────

    @Test
    fun ensure_skipsWhenUsernameAlreadySet() = runTest {
        val repo = mockk<CommunityRepository>(relaxed = true)
        coEvery { repo.getCurrentProfile() } returns CommunityProfile(uid = "u", username = "ahmed")
        assertTrue(repo.ensureSocialProfile(firebaseUser(), "u"))
        coVerify(exactly = 0) { repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun ensure_createsEmailDerivedUsernameAndProviderDisplayName() = runTest {
        val repo = mockk<CommunityRepository>(relaxed = true)
        coEvery { repo.getCurrentProfile() } returns null
        assertTrue(repo.ensureSocialProfile(firebaseUser(), "u"))
        coVerify {
            repo.upsertProfile(
                username = "ahmed_mohamed_test",
                bio = "",
                isPublic = true,
                avatarUrl = null,
                bannerUrl = null,
                displayName = "Ahmed Mohamed",
                location = "",
                birthday = null
            )
        }
    }

    @Test
    fun ensure_fallsBackToEmailPrefixWhenNoProviderName() = runTest {
        val repo = mockk<CommunityRepository>(relaxed = true)
        coEvery { repo.getCurrentProfile() } returns CommunityProfile(uid = "u", username = "")
        assertTrue(repo.ensureSocialProfile(firebaseUser(name = null), "u"))
        coVerify {
            repo.upsertProfile(
                username = "ahmed_mohamed_test",
                bio = "",
                isPublic = true,
                avatarUrl = null,
                bannerUrl = null,
                displayName = "Ahmed.Mohamed+test",
                location = "",
                birthday = null
            )
        }
    }

    @Test
    fun ensure_retriesWithSuffixWhenTakenAndNeverThrows() = runTest {
        val repo = mockk<CommunityRepository>(relaxed = true)
        coEvery { repo.getCurrentProfile() } returns null
        coEvery {
            repo.upsertProfile("ahmed_mohamed_test", any(), any(), any(), any(), any(), any(), any())
        } throws IllegalArgumentException("taken")
        coEvery {
            repo.upsertProfile(match { it != "ahmed_mohamed_test" && it.startsWith("ahmed_mohamed_t") && it.length <= 20 }, any(), any(), any(), any(), any(), any(), any())
        } returns Unit
        assertTrue(repo.ensureSocialProfile(firebaseUser(), "u"))
        coVerify(exactly = 2) { repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun ensure_returnsFalseInsteadOfThrowingWhenAllFail() = runTest {
        val repo = mockk<CommunityRepository>(relaxed = true)
        coEvery { repo.getCurrentProfile() } returns null
        coEvery { repo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("down")
        assertFalse(repo.ensureSocialProfile(firebaseUser(), "u"))
    }
}
