package com.exapps.mangaworld.viewmodel

import android.content.Context
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.sync.PluginSyncScheduler
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.core.source.SourceUiTestFixtures
import com.exapps.mangaworld.presentation.sources.SourcesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v9.1.1 sources actions: the on-demand sync trigger, toggle-clears-held, and
 * the staleness predicate behind boot-time bootstrapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesSyncActionsTest {

    /** Hand fake (mockk answers-capture fights the compiler here; fakes don't). */
    private class FakeIndexStore(
        val map: MutableMap<String, PluginIndexRecord> = mutableMapOf()
    ) : PluginIndexStore {
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        engine: PluginSyncEngine = mockk(relaxed = true),
        index: PluginIndexStore = mockk(relaxed = true),
        settings: SettingsRepository = mockk<SettingsRepository>(relaxed = true).apply {
            every { getAppSettings() } returns
                flowOf(com.exapps.mangaworld.domain.model.AppSettings(enabledSources = setOf("azora")))
            every { isSourceNotificationEnabled(any()) } returns flowOf(true)
        },
        // checkForUpdates() builds File(appContext.filesDir, "plugins") inline:
        // a relaxed Context returns null filesDir, which throws NPE inside the
        // File constructor and forces the failure notice. Stub a real dir.
        appContext: Context = mockk<Context>(relaxed = true).apply {
            every { filesDir } returns tmp.root
        }
    ): SourcesViewModel {
        return SourcesViewModel(
            settingsRepository = settings,
            sourceUiMapper = SourceUiTestFixtures.mapper(),
            sourceRegistry = SourceUiTestFixtures.registry(),
            syncEngine = engine,
            trustKeys = mockk(relaxed = true),
            remoteConfig = mockk(relaxed = true),
            health = mockk(relaxed = true),
            appContext = appContext,
            indexStore = index
        ).also {
            // v9.1.8 lifecycle sink is raw android.util.Log (throws on JVM).
            it.log = {}
        }
    }

    @Test
    fun checkForUpdatesRunsInlineSweepAndNotices() = runTest(dispatcher) {
        val engine: PluginSyncEngine = mockk()
        coEvery {
            engine.sync(any(), any(), any(), any(), any(), any(), any())
        } returns PluginSyncEngine.SyncResult(
            outcomes = mapOf("manonga" to PluginSyncEngine.EntryOutcome.Updated(false))
        )
        val viewModel = vm(engine = engine)
        advanceUntilIdle()
        val notices = mutableListOf<Int>()
        val collect = launch { viewModel.notice.collect { notices += it } }
        advanceUntilIdle()
        viewModel.checkForUpdates()
        advanceUntilIdle()
        assertTrue(notices.contains(com.exapps.mangaworld.R.string.plugin_check_updated))
        assertEquals(false, viewModel.syncing.value)
        collect.cancel()
    }

    @Test
    fun checkForUpdatesFailureNotices() = runTest(dispatcher) {
        val engine: PluginSyncEngine = mockk()
        coEvery {
            engine.sync(any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("boom")
        val viewModel = vm(engine = engine)
        advanceUntilIdle()
        val notices = mutableListOf<Int>()
        val collect = launch { viewModel.notice.collect { notices += it } }
        advanceUntilIdle()
        viewModel.checkForUpdates()
        advanceUntilIdle()
        assertTrue(notices.contains(com.exapps.mangaworld.R.string.plugin_check_failed))
        assertEquals(false, viewModel.syncing.value)
        collect.cancel()
    }

    @Test
    fun toggleOnClearsHeldBadge() = runTest(dispatcher) {
        val held = PluginIndexRecord(
            id = "gated", activeVersion = 1, previousVersion = null,
            origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
            manifestJson = """{"id":"gated"}"""
        )
        val store = FakeIndexStore(mutableMapOf("gated" to held))
        val viewModel = vm(index = store)
        advanceUntilIdle()
        viewModel.toggleSource("gated", true)
        advanceUntilIdle()
        assertEquals(PluginStatus.ENABLED, store.map["gated"]!!.status)
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
