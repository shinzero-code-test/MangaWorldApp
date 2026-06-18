package com.exapps.mangaworld.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.exapps.mangaworld.core.data.local.AppPreferences
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
}
