package com.exapps.mangaworld.device

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dataStore
import com.exapps.mangaworld.domain.model.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device DataStore checks (Firebase Test Lab): the same round-trips the
 * Robolectric suite covers on JVM, verified against real Android
 * SharedPreferences-backed DataStore files.
 */
@RunWith(AndroidJUnit4::class)
class AppPreferencesDeviceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() = AppPreferences(context)

    @Before
    fun clearStore() = runTest {
        context.dataStore.edit { it.clear() }
    }

    @Test
    fun defaults_matchProductExpectations() = runTest {
        val s = prefs.appSettings.first()
        assertEquals(AppTheme.DARK, s.theme)
        assertTrue(s.enableNotifications)
        assertTrue(s.downloadOnWifiOnly)
    }

    @Test
    fun theme_roundTripsOnDevice() = runTest {
        prefs.setTheme(AppTheme.LIGHT)
        assertEquals(AppTheme.LIGHT, prefs.appSettings.first().theme)
    }
}
