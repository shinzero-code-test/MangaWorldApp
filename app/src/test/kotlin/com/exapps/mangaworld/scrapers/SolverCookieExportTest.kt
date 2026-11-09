package com.exapps.mangaworld.scrapers

import com.exapps.mangaworld.presentation.webview.extractClearanceCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-23: solver exports only clearance cookies (deny-by-default).
 */
class SolverCookieExportTest {

    @Test
    fun keepsOnlyClearance() {
        val raw = "cf_clearance=abc123; session=secret; __cf_bm=xyz; other=1"
        assertEquals("cf_clearance=abc123", extractClearanceCookies(raw))
    }

    @Test
    fun dropsWholeJarWhenNoClearance() {
        assertEquals("", extractClearanceCookies("session=secret; foo=bar"))
    }

    @Test
    fun dedupsAndTrims() {
        val raw = "  cf_clearance=a ; cf_clearance=a; cf_clearance=b"
        assertEquals("cf_clearance=a; cf_clearance=b", extractClearanceCookies(raw))
    }

    @Test
    fun blankIsEmpty() {
        assertEquals("", extractClearanceCookies(""))
        assertEquals("", extractClearanceCookies("   "))
    }

    @Test
    fun apiHostOverrideNormalizes() {
        assertEquals("api.example.com", com.exapps.mangaworld.domain.model.SourceDomainOverrides.normalizeApiHost("https://api.example.com/"))
        assertEquals("api.example.com", com.exapps.mangaworld.domain.model.SourceDomainOverrides.normalizeApiHost("api.example.com"))
        assertTrue(com.exapps.mangaworld.domain.model.SourceDomainOverrides.normalizeApiHost("api.example.com/path") == null)
        assertTrue(com.exapps.mangaworld.domain.model.SourceDomainOverrides.normalizeApiHost("api.example.com:8443") == null)
        assertFalse(false)
    }
}
