package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.core.source.plugins.HostPolicy.RedirectDecision
import com.exapps.mangaworld.core.source.plugins.HostPolicy.RedirectRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: every redirect hop is validated independently — destination allow-list,
 * no protocol downgrade, capped hop budget. Auto-follow stays off; this is the manual
 * per-hop check the engine will run instead.
 */
class RedirectHopValidationTest {

    private val allowed = setOf("starzmanga.com", "cdn.starzmanga.com")
    private val current = "https://starzmanga.com/manga/slug"

    @Test
    fun relativeAndAbsoluteSameHostFollow() {
        val rel = HostPolicy.resolveRedirect(current, "/manga/other", allowed, hopsUsed = 0)
        assertTrue(rel is RedirectDecision.Follow)
        assertEquals("https://starzmanga.com/manga/other", (rel as RedirectDecision.Follow).url)

        val abs = HostPolicy.resolveRedirect(current, "https://cdn.starzmanga.com/i.png", allowed, 0)
        assertTrue(abs is RedirectDecision.Follow)
    }

    @Test
    fun offAllowListHopRejected() {
        val result = HostPolicy.resolveRedirect(current, "https://evil.com/x", allowed, 0)
        assertTrue(result is RedirectDecision.Reject)
        assertEquals(RedirectRejectReason.HOST_NOT_ALLOWED, (result as RedirectDecision.Reject).reason)
    }

    @Test
    fun lookalikeHopRejected() {
        val result = HostPolicy.resolveRedirect(current, "https://starzmanga.com.evil.com/", allowed, 0)
        assertTrue(result is RedirectDecision.Reject)
        assertEquals(RedirectRejectReason.HOST_NOT_ALLOWED, (result as RedirectDecision.Reject).reason)
    }

    @Test
    fun downgradeRejectedAtAnyHop() {
        val result = HostPolicy.resolveRedirect(current, "http://starzmanga.com/plain", allowed, hopsUsed = 3)
        assertTrue(result is RedirectDecision.Reject)
        assertEquals(RedirectRejectReason.NOT_HTTPS, (result as RedirectDecision.Reject).reason)
    }

    @Test
    fun hopBudgetEnforced() {
        val ok = HostPolicy.resolveRedirect(current, "/a", allowed, hopsUsed = HostPolicy.MAX_REDIRECT_HOPS - 1)
        assertTrue(ok is RedirectDecision.Follow)
        val over = HostPolicy.resolveRedirect(current, "/a", allowed, hopsUsed = HostPolicy.MAX_REDIRECT_HOPS)
        assertTrue(over is RedirectDecision.Reject)
        assertEquals(RedirectRejectReason.HOP_BUDGET_EXCEEDED, (over as RedirectDecision.Reject).reason)
    }

    @Test
    fun missingAndGarbageLocation() {
        assertTrue(HostPolicy.resolveRedirect(current, null, allowed, 0) is RedirectDecision.NoRedirect)
        assertTrue(HostPolicy.resolveRedirect(current, "   ", allowed, 0) is RedirectDecision.NoRedirect)
        // Unparseable Location (control chars are illegal in URIs) fails closed.
        val bad = HostPolicy.resolveRedirect(current, "https://exa mple.com/", allowed, 0)
        assertTrue(bad is RedirectDecision.Reject)
    }
}
