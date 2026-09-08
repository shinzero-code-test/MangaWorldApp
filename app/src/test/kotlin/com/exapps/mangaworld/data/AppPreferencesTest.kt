package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.local.AppPreferences
import org.junit.Assert.*
import org.junit.Test

// NOTE: only the static cookieKey helper is unit-testable on JVM — the
// DataStore flows need an instrumented test (no Robolectric/emulator in CI).
class AppPreferencesTest {
    @Test
    fun cookieKey_returnsCorrectKey() {
        val key = AppPreferences.cookieKey("example.com")
        assertEquals("cookie_example.com", key.name)
    }

    @Test
    fun cookieKey_differentDomains_differentKeys() {
        val key1 = AppPreferences.cookieKey("domain1.com")
        val key2 = AppPreferences.cookieKey("domain2.com")
        assertNotEquals(key1.name, key2.name)
    }

    @Test
    fun cookieKey_edgeCases() {
        // Case and whitespace variants of one domain share one key; distinct
        // hosts (incl. subdomains) never collide.
        assertEquals(
            AppPreferences.cookieKey("Example.COM").name,
            AppPreferences.cookieKey("example.com").name
        )
        assertNotEquals(
            AppPreferences.cookieKey("example.com").name,
            AppPreferences.cookieKey("sub.example.com").name
        )
    }
}
