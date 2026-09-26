package com.exapps.mangaworld.viewmodel

import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.sync.PluginSyncScheduler
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.core.source.SourceUiTestFixtures
import com.exapps.mangaworld.presentation.sources.SourcesViewModel
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v9.1.1 sources actions: the on-demand sync trigger, toggle-clears-held, and
 * the staleness predicate behind boot-time bootstrapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesSyncActionsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        scheduler: PluginSyncScheduler = mockk(relaxed = true),
        engine: PluginSyncEngine = mockk(relaxed = true),
        index: PluginIndexStore = mockk(relaxed = true),
        settings: SettingsRepository = mockk<SettingsRepository>(relaxed = true).apply {
            every { getAppSettings() } returns
                flowOf(com.exapps.mangaworld.domain.model.AppSettings(enabledSources = setOf("azora")))
            every { isSourceNotificationEnabled(any()) } returns flowOf(true)
        }
    ): SourcesViewModel {
        return SourcesViewModel(
            settingsRepository = settings,
            sourceUiMapper = SourceUiTestFixtures.mapper(),
            sourceRegistry = SourceUiTestFixtures.registry(),
            pluginSyncScheduler = scheduler,
            syncEngine = engine,
            trustKeys = mockk(relaxed = true),
            remoteConfig = mockk(relaxed = true),
            health = mockk(relaxed = true),
            appContext = mockk(relaxed = true),
            indexStore = index
        )
    }

    @Test
    fun checkForUpdatesEnqueuesOneShot() = runTest(dispatcher) {
        val scheduler: PluginSyncScheduler = mockk(relaxed = true)
        val viewModel = vm(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.checkForUpdates()
        advanceUntilIdle()
        coVerify { scheduler.requestNow() }
    }

    @Test
    fun toggleOnClearsHeldBadge() = runTest(dispatcher) {
        val held = PluginIndexRecord(
            id = "gated", activeVersion = 1, previousVersion = null,
            origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
            manifestJson = """{"id":"gated"}"""
        )
        var stored: PluginIndexRecord = held
        val index: PluginIndexStore = mockk {
            coEvery { get("gated") } coAnswers { stored }
            coEvery { put(any()) } coAnswers {
                stored = firstArg()
                Unit
            }
        }
        val viewModel = vm(index = index)
        advanceUntilIdle()
        viewModel.toggleSource("gated", true)
        advanceUntilIdle()
        assertEquals(PluginStatus.ENABLED, stored.status)
    }

    @Test
    fun toggleOffLeavesIndexAlone() = runTest(dispatcher) {
        val index: PluginIndexStore = mockk(relaxed = true)
        val viewModel = vm(index = index)
        advanceUntilIdle()
        viewModel.toggleSource("gated", false)
        advanceUntilIdle()
        coVerify(exactly = 0) { index.put(any()) }
    }

    @Test
    fun stalePredicate() {
        // Never synced → stale; fresh → quiet; boundary → stale.
        assertTrue(PluginSyncScheduler.isStale(0L, 1_000L, 100L))
        assertTrue(!PluginSyncScheduler.isStale(950L, 1_000L, 100L))
        assertTrue(PluginSyncScheduler.isStale(900L, 1_000L, 100L))
        assertTrue(!PluginSyncScheduler.isStale(2_000L, 1_000L, 100L))
    }
}
