package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: allow-list matching is structural and exact. Substring games,
 * lookalike suffixes, userinfo tricks and IP literals must all fail closed.
 */
class HostPolicyTest {

    private val allowed = setOf("starzmanga.com", "cdn.starzmanga.com")

    @Test
    fun exactHostsAllowed() {
        assertTrue(HostPolicy.isHostAllowed("starzmanga.com", allowed))
        assertTrue(HostPolicy.isHostAllowed("cdn.starzmanga.com", allowed))
    }

    @Test
    fun matchingIgnoresCaseAndTrailingDot() {
        assertTrue(HostPolicy.isHostAllowed("StarzManga.COM", allowed))
        assertTrue(HostPolicy.isHostAllowed("starzmanga.com.", allowed))
    }

    @Test
    fun subdomainNeverImplied() {
        assertFalse(HostPolicy.isHostAllowed("img.starzmanga.com", allowed))
        assertFalse(HostPolicy.isHostAllowed("other.cdn.starzmanga.com", allowed))
    }

    @Test
    fun lookalikeSuffixesRejected() {
        assertFalse(HostPolicy.isHostAllowed("evilstarmanga.com", allowed))
        assertFalse(HostPolicy.isHostAllowed("starzmanga.com.evil.com", allowed))
        assertFalse(HostPolicy.isHostAllowed("starzmanga-com.evil.com", allowed))
        assertFalse(HostPolicy.isHostAllowed("notcdn.starzmanga.com", allowed))
    }

    @Test
    fun emptyAndGarbageRejected() {
        assertFalse(HostPolicy.isHostAllowed("", allowed))
        assertFalse(HostPolicy.isHostAllowed("..", allowed))
    }

    @Test
    fun hostOfStripsUserinfoAndPort() {
        // userinfo-embedded URLs resolve to their real host — matched structurally.
        assertEquals("evil.com", HostPolicy.hostOf("https://user@evil.com/path"))
        assertEquals("cdn.starzmanga.com", HostPolicy.hostOf("https://cdn.starzmanga.com:8443/i.png"))
        assertEquals("", HostPolicy.hostOf("not a url"))
        assertEquals("", HostPolicy.hostOf("/relative/path"))
    }

    @Test
    fun allowListEntriesValidated() {
        assertTrue(HostPolicy.isValidAllowListEntry("starzmanga.com"))
        assertTrue(HostPolicy.isValidAllowListEntry("a.b.c.example.com"))
        assertTrue(HostPolicy.isValidAllowListEntry("1.2.3.4"))
        assertFalse(HostPolicy.isValidAllowListEntry("*.starzmanga.com"))
        assertFalse(HostPolicy.isValidAllowListEntry("starzmanga.com:8443"))
        assertFalse(HostPolicy.isValidAllowListEntry("user@starzmanga.com"))
        assertFalse(HostPolicy.isValidAllowListEntry("starz manga.com"))
        assertFalse(HostPolicy.isValidAllowListEntry("2001:db8::1"))
        assertFalse(HostPolicy.isValidAllowListEntry(""))
        assertFalse(HostPolicy.isValidAllowListEntry("a".repeat(254)))
    }
}
