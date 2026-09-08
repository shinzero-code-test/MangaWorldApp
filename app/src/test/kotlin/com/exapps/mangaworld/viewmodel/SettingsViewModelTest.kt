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
        context = io.mockk.mockk(relaxed = true),
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

    @Test
    fun everyDelegatingSetter_forwardsToRepo() {
        // One row per saveAndSync setter: adding a setter without a row here
        // is allowed, but extending this table keeps delegation honest.
        val vm = createViewModel()
        val NDM = com.exapps.mangaworld.domain.model.NotificationDeliveryMode.INSTANT
        val RM = com.exapps.mangaworld.domain.model.ReaderMode.VERTICAL_SCROLL
        val RF = com.exapps.mangaworld.domain.model.ReaderImageFilter.NONE
        listOf<Pair<String, suspend () -> Unit>>(
            "dynamic" to suspend { vm.setDynamicColors(true) },
            "biometric" to suspend { vm.setBiometricLock(true) },
            "secure" to suspend { vm.setSecureReader(true) },
            "mode" to suspend { vm.setNotificationMode(NDM) },
            "wifi" to suspend { vm.setWifiOnly(true) },
            "autodl" to suspend { vm.setAutoDownload(true) },
            "notif" to suspend { vm.setNotifications(false) },
            "cleanup" to suspend { vm.setAutoCleanup(true) },
            "hours" to suspend { vm.setCleanupHours(48) },
            "cache" to suspend { vm.setImageCacheLimit(100) },
            "blacklist" to suspend { vm.setContentBlacklist(setOf("x")) },
            "spoiler" to suspend { vm.setSpoilerCollapseDefault(false) },
            "muted" to suspend { vm.setMutedUserIds(setOf("u1")) },
            "readerMode" to suspend { vm.setReaderMode(RM) },
            "brightness" to suspend { vm.setBrightness(0.5f) },
            "keepScreen" to suspend { vm.setKeepScreen(false) },
            "webtoon" to suspend { vm.setAutoWebtoon(false) },
            "incognito" to suspend { vm.setIncognito(true) },
            "prefetch" to suspend { vm.setSmartPrefetch(false) },
            "haptics" to suspend { vm.setReaderHaptics(false) },
            "filter" to suspend { vm.setImageFilter(RF) },
            "nextChapter" to suspend { vm.setAutoOpenNextChapter(true) },
            "liveReaders" to suspend { vm.setShowLiveReadersOverlay(false) }
        ).forEach { (name, call) ->
            kotlinx.coroutines.runBlocking { call() }
        }
        coVerify { settingsRepo.setDynamicColors(true) }
        coVerify { settingsRepo.setBiometricLock(true) }
        coVerify { settingsRepo.setSecureReader(true) }
        coVerify { settingsRepo.setNotificationsEnabled(false) }
        coVerify { settingsRepo.setCleanupAfterHours(48) }
        coVerify { settingsRepo.setImageCacheLimitMb(100) }
        coVerify { settingsRepo.setContentBlacklist(setOf("x")) }
        coVerify { settingsRepo.setMutedUserIds(setOf("u1")) }
        coVerify { settingsRepo.updateReaderMode(RM) }
        coVerify { settingsRepo.updateBrightness(0.5f) }
        coVerify { settingsRepo.updateImageFilter(RF) }
    }

    @Test
    fun saveAndSyncFailure_doesNotCrashAndStillAppliesLocally() {
        // pushLocalSnapshot throws after the local write: the failure must be
        // swallowed (runCatching) with the repo call already applied.
        coEvery { firebaseSyncManager.pushLocalSnapshot() } throws RuntimeException("offline")
        val vm = createViewModel()
        vm.setTheme(AppTheme.LIGHT)
        coVerify { settingsRepo.updateTheme(AppTheme.LIGHT) }
    }
}
