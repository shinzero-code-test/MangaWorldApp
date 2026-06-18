package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.data.CacheManager
import com.exapps.mangaworld.core.data.LocalBackupManager
import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
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
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val localBackupManager = mockk<LocalBackupManager>(relaxed = true)
    private val widgetDataRepository = mockk<WidgetDataRepository>(relaxed = true)
    private val firebaseSyncManager = mockk<FirebaseSyncManager>(relaxed = true)
    private val widgetShortcutCoordinator = mockk<WidgetShortcutCoordinator>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { settingsRepo.getReaderSettings() } returns flowOf(
            com.exapps.mangaworld.domain.model.ReaderSettings()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(
        repo = settingsRepo,
        cacheManager = cacheManager,
        localBackupManager = localBackupManager,
        widgetDataRepository = widgetDataRepository,
        firebaseSyncManager = firebaseSyncManager,
        widgetShortcutCoordinator = widgetShortcutCoordinator
    )

    @Test
    fun initialState_hasDefaultSettings() {
        val vm = createViewModel()
        assertEquals(AppTheme.DARK, vm.appSettings.value.theme)
        assertTrue(vm.appSettings.value.enableNotifications)
    }

    @Test
    fun setTheme_callsSettingsRepo() {
        val vm = createViewModel()
        vm.setTheme(AppTheme.LIGHT)
        coVerify { settingsRepo.updateTheme(AppTheme.LIGHT) }
    }

    @Test
    fun toggleSource_callsSettingsRepo() {
        val vm = createViewModel()
        vm.toggleSource("azora", false)
        coVerify { settingsRepo.toggleSource("azora", false) }
    }
}
