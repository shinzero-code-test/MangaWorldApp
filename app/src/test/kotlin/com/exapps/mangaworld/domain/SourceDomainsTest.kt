package com.exapps.mangaworld.domain

import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-12: override origins are bare `https://host` only. The app sets
 * `usesCleartextTraffic="false"`, so an http value would silently brick the
 * source; explicit ports/paths are undeclared channels/scope. Mirrors
 * `ManifestParser.validateBaseUrl` discipline for unsigned RC values.
 */
class SourceDomainsTest {

    @Test
    fun cleanHttpsOriginsAccepted() {
        assertEquals(
            "https://starzmanga.com",
            SourceDomainOverrides.normalizeBaseUrl("https://starzmanga.com")
        )
        assertEquals(
            "https://starzmanga.com",
            SourceDomainOverrides.normalizeBaseUrl("  https://StarzManga.COM/  ")
        )
    }

    @Test
    fun httpPortsAndPathsRejected() {
        assertNull(SourceDomainOverrides.normalizeBaseUrl("http://starzmanga.com"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("https://starzmanga.com:8443"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("https://starzmanga.com:443"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("https://starzmanga.com/manga"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("https://starzmanga.com/?x=1"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("not a url"))
        assertNull(SourceDomainOverrides.normalizeBaseUrl(""))
        assertNull(SourceDomainOverrides.normalizeBaseUrl(null))
        assertNull(SourceDomainOverrides.normalizeBaseUrl("https://user@starzmanga.com"))
    }
}
