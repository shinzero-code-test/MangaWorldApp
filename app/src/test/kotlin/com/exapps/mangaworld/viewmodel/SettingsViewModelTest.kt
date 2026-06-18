package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseTelemetry
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.settings.SettingsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val remoteConfigManager = mockk<FirebaseRemoteConfigManager>(relaxed = true)
    private val firebaseTelemetry = mockk<FirebaseTelemetry>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { settingsRepo.getReaderSettings() } returns flowOf(
            com.exapps.mangaworld.domain.model.ReaderSettings()
        )
        every { remoteConfigManager.remoteAlertMessage } returns flowOf("")
        every { remoteConfigManager.disabledSourceIds } returns flowOf(emptySet())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(
        settingsRepo = settingsRepo,
        remoteConfigManager = remoteConfigManager,
        firebaseTelemetry = firebaseTelemetry
    )

    @Test
    fun initialState_hasDefaultSettings() {
        val vm = createViewModel()
        assertEquals(AppTheme.DARK, vm.settings.value.theme)
        assertTrue(vm.settings.value.enableNotifications)
    }

    @Test
    fun setTheme_callsSettingsRepo() {
        val vm = createViewModel()
        vm.setTheme(AppTheme.LIGHT)
        coVerify { settingsRepo.updateTheme(AppTheme.LIGHT) }
    }

    @Test
    fun setNotificationsEnabled_callsSettingsRepo() {
        val vm = createViewModel()
        vm.setNotificationsEnabled(false)
        coVerify { settingsRepo.setNotificationsEnabled(false) }
    }

    @Test
    fun toggleSource_callsSettingsRepo() {
        val vm = createViewModel()
        vm.toggleSource("azora", false)
        coVerify { settingsRepo.toggleSource("azora", false) }
    }
}
