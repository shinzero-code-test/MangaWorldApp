package com.exapps.mangaworld.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-36/U-31 deep-link trust gate: exported `mangaworld://reader` links must
 * only ever resolve chapters on the source's own host. Relative paths are
 * trusted (the reader resolves them against the source base URL); anything
 * absolute must be http(s) with a matching host.
 */
class TrustedChapterHostTest {

    private val base = "https://example.com"

    @Test
    fun `relative path is trusted`() {
        assertTrue(isTrustedChapterHost("/manga/slug/chapter-1", base))
    }

    @Test
    fun `relative path with colon after slash is trusted`() {
        assertTrue(isTrustedChapterHost("/manga/a:b/chapter-1", base))
    }

    @Test
    fun `absolute https same host is trusted`() {
        assertTrue(isTrustedChapterHost("https://example.com/manga/slug/chapter-1", base))
    }

    @Test
    fun `absolute http same host is trusted`() {
        assertTrue(isTrustedChapterHost("http://example.com/manga/slug", base))
    }

    @Test
    fun `absolute https other host is rejected`() {
        assertFalse(isTrustedChapterHost("https://evil.com/manga/slug/chapter-1", base))
    }

    @Test
    fun `host match is case insensitive and ignores subdomains`() {
        assertTrue(isTrustedChapterHost("https://EXAMPLE.com/manga/slug", base))
        assertFalse(isTrustedChapterHost("https://sub.example.com/manga/slug", base))
    }

    @Test
    fun `protocol relative link is rejected`() {
        assertFalse(isTrustedChapterHost("//evil.com/manga/slug", base))
    }

    @Test
    fun `javascript uri is rejected`() {
        assertFalse(isTrustedChapterHost("javascript:alert(1)", base))
    }

    @Test
    fun `file and content uris are rejected`() {
        assertFalse(isTrustedChapterHost("file:///etc/passwd", base))
        assertFalse(isTrustedChapterHost("content://com.android.providers.media.documents/document/1", base))
    }

    @Test
    fun `non http absolute uri is rejected`() {
        assertFalse(isTrustedChapterHost("ftp://example.com/manga/slug", base))
    }

    @Test
    fun `unusable base url rejects absolute links`() {
        assertFalse(isTrustedChapterHost("https://example.com/manga/slug", "not a url"))
    }
}
