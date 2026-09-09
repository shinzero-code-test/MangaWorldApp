package com.exapps.mangaworld.data

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dataStore
import com.exapps.mangaworld.domain.model.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real DataStore tests under Robolectric (#16). The plain-JVM
 * [AppPreferencesTest] can only cover the static `cookieKey` helper; flows
 * need a Context, which Robolectric provides headlessly in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppPreferencesDataStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() = AppPreferences(context)

    @Before
    fun clearDataStore() = runTest {
        context.dataStore.edit { it.clear() }
    }

    @Test
    fun defaults_matchProductExpectations() = runTest {
        val s = prefs.appSettings.first()
        assertEquals(AppTheme.DARK, s.theme)
        assertTrue(s.enableNotifications)
        assertTrue(s.downloadOnWifiOnly)
        assertFalse(s.autoDownloadNewChapters)
        assertFalse(s.onboardingCompleted)
        // All sources enabled out of the box.
        assertEquals(com.exapps.mangaworld.domain.model.MangaSource.entries.map { it.id }.toSet(), s.enabledSources)
    }

    @Test
    fun theme_roundTrips() = runTest {
        prefs.setTheme(AppTheme.LIGHT)
        assertEquals(AppTheme.LIGHT, prefs.appSettings.first().theme)
        prefs.setTheme(AppTheme.DARK)
        assertEquals(AppTheme.DARK, prefs.appSettings.first().theme)
    }

    @Test
    fun wifiOnly_roundTrips() = runTest {
        prefs.setDownloadWifiOnly(false)
        assertFalse(prefs.appSettings.first().downloadOnWifiOnly)
        prefs.setDownloadWifiOnly(true)
        assertTrue(prefs.appSettings.first().downloadOnWifiOnly)
    }
}
