package com.exapps.mangaworld.core.source

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.source.plugins.BundledPluginLoader
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginStore
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
import com.exapps.mangaworld.core.source.plugins.PluginUpgradeReconciler
import com.exapps.mangaworld.core.source.plugins.PrefsAppVersionStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Gate 0 boot path: `BundledPluginLoader.bootstrap()` emits one aggregate
 * telemetry event per boot (activated ids bounded, skips collapsed by reason,
 * reconciler count) instead of logcat-only lines.
 *
 * Pilots verify against the REAL pinned key and on-disk pilot assets (same
 * proof as `BundledPilotTest`); the fixture registry intentionally mismatches
 * pilot engines (MADARA fixtures vs MANGAREADER pilots), so both pilots land
 * in `skipped` — which is exactly the aggregate the fleet view must show for
 * a packaging mismatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginBootstrapTelemetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeIndex : PluginIndexStore {
        val map = mutableMapOf<String, PluginIndexRecord>()
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private fun assetBytes(path: String): ByteArray {
        // Unit-test workdir is the :app module dir (BundledPilotTest precedent).
        val candidates = listOf(
            File("src/main/assets/$path"),
            File(System.getProperty("user.dir"), "src/main/assets/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readBytes()
            ?: error("asset missing: $path")
    }

    private fun loader(telemetry: RecordingPluginTelemetry): BundledPluginLoader {
        val assetManager: AssetManager = mockk()
        every { assetManager.open(any()) } answers {
            assetBytes(firstArg<String>()).inputStream()
        }
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk {
            every { getString(any(), any()) } returns null
            every { edit() } returns editor
        }
        val context: Context = mockk()
        every { context.filesDir } returns tmp.root
        every { context.assets } returns assetManager
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val index = FakeIndex()
        val trustKeys: PluginTrustKeys = mockk {
            every { current() } returns PluginTrust.pinnedKeys()
        }
        return BundledPluginLoader(
            context = context,
            registry = SourceUiTestFixtures.registry("hijala", "lavascans"),
            store = PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined),
            index = index,
            trustKeys = trustKeys,
            runnerFactory = mockk(relaxed = true),
            reconciler = PluginUpgradeReconciler(
                index,
                PluginStore(index, kotlinx.coroutines.Dispatchers.Unconfined),
                trustKeys,
                kotlinx.coroutines.Dispatchers.Unconfined
            ),
            appVersionStore = PrefsAppVersionStore(context),
            telemetry = telemetry,
            io = kotlinx.coroutines.Dispatchers.Unconfined
        )
    }

    @Test
    fun bootstrapEmitsAggregateReport() = runTest {
        val telemetry = RecordingPluginTelemetry()
        val outcomes = loader(telemetry).bootstrap()
        assertEquals(2, outcomes.size)
        // Both pilots skip (engine mismatch vs fixture registry) — the report
        // must show the skips collapsed by reason, not silence.
        val report = telemetry.bootstraps.single()
        assertTrue(report.activatedIds.isEmpty())
        assertTrue(report.skippedByReason.isNotEmpty())
        assertEquals(0, report.reconciled)
        assertEquals(BuildConfig.VERSION_NAME, report.appVersion)
    }
}
