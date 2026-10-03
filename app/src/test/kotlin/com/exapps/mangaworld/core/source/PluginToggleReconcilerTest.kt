package com.exapps.mangaworld.core.source

import android.content.Context
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
import com.exapps.mangaworld.core.source.sync.PluginSyncEngine
import com.exapps.mangaworld.core.source.sync.PluginToggleReconciler
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D6: both source toggles funnel through one reconciler. Enabling a held
 * row (DISABLED + verifiable payload) runs approval instead of a bare flip
 * that would strand the row with nothing registered; disabling never touches
 * the index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginToggleReconcilerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeIndex(val map: MutableMap<String, PluginIndexRecord> = mutableMapOf()) :
        PluginIndexStore {
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private fun reconciler(
        engine: PluginSyncEngine = mockk(relaxed = true),
        settings: SettingsRepository = mockk(relaxed = true),
        index: PluginIndexStore = mockk(relaxed = true)
    ): PluginToggleReconciler {
        val trustKeys: PluginTrustKeys = mockk(relaxed = true)
        val remoteConfig: FirebaseRemoteConfigManager = mockk(relaxed = true)
        val appContext: Context = mockk<Context>(relaxed = true).apply {
            every { filesDir } returns tmp.root
        }
        return PluginToggleReconciler(
            engine = engine,
            settingsRepo = settings,
            trustKeys = trustKeys,
            remoteConfig = remoteConfig,
            indexStore = index,
            appContext = appContext
        )
    }

    private fun held(id: String = "gated") = PluginIndexRecord(
        id = id, activeVersion = 1, previousVersion = null,
        origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
        manifestJson = """{"id":"$id"}"""
    )

    @Test
    fun disableOnlyFlipsSettings() = runTest {
        val settings: SettingsRepository = mockk(relaxed = true)
        val index = FakeIndex(mutableMapOf("gated" to held()))
        val outcome = reconciler(settings = settings, index = index).setEnabled("gated", false)
        assertTrue(outcome is PluginToggleReconciler.ToggleOutcome.Disabled)
        coVerify { settings.toggleSource("gated", false) }
        // Index untouched (re-enable stays one tap).
        assertTrue(index.map["gated"]!!.status == PluginStatus.DISABLED)
    }

    @Test
    fun plainEnableFlipsSettingsAndBadge() = runTest {
        val settings: SettingsRepository = mockk(relaxed = true)
        val outcome = reconciler(settings = settings).setEnabled("azora", true)
        assertTrue(outcome is PluginToggleReconciler.ToggleOutcome.Enabled)
        coVerify { settings.toggleSource("azora", true) }
    }

    @Test
    fun heldEnableRunsApproval() = runTest {
        val engine: PluginSyncEngine = mockk()
        coEvery {
            engine.approveHeld(any(), any(), any(), any(), any(), any())
        } returns PluginSyncEngine.ApproveOutcome.Approved
        val settings: SettingsRepository = mockk(relaxed = true)
        val index = FakeIndex(mutableMapOf("gated" to held()))
        val outcome = reconciler(engine = engine, settings = settings, index = index)
            .setEnabled("gated", true)
        assertTrue(outcome is PluginToggleReconciler.ToggleOutcome.Approved)
        // Routed to approval (which owns the settings flip), not a bare flip.
        coVerify { engine.approveHeld(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedApprovalLeavesSettingsOff() = runTest {
        val engine: PluginSyncEngine = mockk()
        coEvery {
            engine.approveHeld(any(), any(), any(), any(), any(), any())
        } returns PluginSyncEngine.ApproveOutcome.Failed("smoke failed")
        val settings: SettingsRepository = mockk(relaxed = true)
        val index = FakeIndex(mutableMapOf("gated" to held()))
        val outcome = reconciler(engine = engine, settings = settings, index = index)
            .setEnabled("gated", true)
        assertTrue(outcome is PluginToggleReconciler.ToggleOutcome.ApprovalFailed)
        coVerify(exactly = 0) { settings.toggleSource("gated", true) }
    }
}
