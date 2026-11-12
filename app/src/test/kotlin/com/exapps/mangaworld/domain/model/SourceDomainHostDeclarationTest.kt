package com.exapps.mangaworld.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-31 (2.4): a Remote Config host override is only honoured when the
 * source already DECLARES that host. Otherwise an RC/console compromise
 * could repoint a source at an attacker host — and because the shared host
 * gate builds its allow-list from the resolved base URL, it widened the gate
 * as well (the same class of failure that blanked every image in v9.6.0).
 */
class SourceDomainHostDeclarationTest {

    private val declared = setOf("azorafly.com", "api.azorafly.com", "storage.azorafly.com")

    @Test
    fun `a declared host is accepted`() {
        assertTrue(SourceDomainOverrides.isHostDeclared("azorafly.com", declared))
        assertTrue(SourceDomainOverrides.isHostDeclared("api.azorafly.com", declared))
    }

    @Test
    fun `case and trailing dot are normalised`() {
        assertTrue(SourceDomainOverrides.isHostDeclared("API.AzoraFly.com.", declared))
    }

    @Test
    fun `an undeclared host is refused`() {
        assertFalse(SourceDomainOverrides.isHostDeclared("evil.example.com", declared))
        // A subdomain of a declared host is NOT automatically declared: the
        // descriptor lists the CDN/API subdomains it uses explicitly.
        assertFalse(SourceDomainOverrides.isHostDeclared("cdn.evil.azorafly.com", declared))
    }

    @Test
    fun `blank input is refused`() {
        assertFalse(SourceDomainOverrides.isHostDeclared("", declared))
        assertFalse(SourceDomainOverrides.isHostDeclared("   ", declared))
    }

    @Test
    fun `an empty declaration set refuses everything`() {
        assertFalse(SourceDomainOverrides.isHostDeclared("azorafly.com", emptySet()))
    }
}
