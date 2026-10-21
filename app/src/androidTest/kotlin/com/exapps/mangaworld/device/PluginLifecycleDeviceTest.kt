package com.exapps.mangaworld.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exapps.mangaworld.core.source.plugins.BundledPluginLoader
import com.exapps.mangaworld.core.source.plugins.PluginDiagnosticsEntryPoint
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.rowStateFor
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device plugin invariants (Firebase Test Lab).
 *
 * These are the checks the JVM suite CANNOT make, because they need the real
 * APK assets, the real Room index and the real boot path:
 *
 *  - the bundled pilots are installed and activated from `assets/plugins/...`;
 *  - **no enabled source lacks a runner** — that is the exact condition that
 *    made a source disappear from every list on the 9.2.3 cold start (a record
 *    with a version but no runner is filtered out of Home/Browse/Search, so
 *    the user sees nothing and no error);
 *  - re-running the bootstrap does not move a pointer backwards (v9.3.4: a
 *    bundled asset overwrote a newer synced version on every cold start);
 *  - every record maps to a VISIBLE row state — a state the user can reach
 *    must be visible with a reason, never silently absent.
 *
 * Test Lab installs the app fresh on every run, so this is the only place the
 * *first-install* path gets exercised continuously; the 9.2.3 mystery was a
 * first-install/cold-start symptom that no long-lived device reproduced.
 */
@RunWith(AndroidJUnit4::class)
class PluginLifecycleDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val entry: PluginDiagnosticsEntryPoint by lazy {
        EntryPointAccessors.fromApplication(context, PluginDiagnosticsEntryPoint::class.java)
    }

    private val index: PluginIndexStore get() = entry.pluginIndexStore()
    private val registry: SourceRegistry get() = entry.sourceRegistry()
    private val loader: BundledPluginLoader get() = entry.bundledPluginLoader()

    @Before
    fun waitForBoot() = runBlocking {
        // The app boots the pilots in its own coroutine (v9.3.5 moved it ahead
        // of the network-bound startup). Wait for it instead of racing it.
        withTimeout(BOOT_TIMEOUT_MS) {
            while (index.getAll().isEmpty()) {
                kotlinx.coroutines.delay(200)
            }
        }
    }

    @Test
    fun bundledPilotsAreActivatedOnDevice() = runBlocking {
        val records = index.getAll()
        assertTrue(
            "the bundled pilots must leave activation records, got $records",
            records.isNotEmpty()
        )
        val activated = records.filter { it.status == PluginStatus.ENABLED }
        assertTrue(
            "at least one pilot must be ENABLED, got ${records.map { it.id to it.status }}",
            activated.isNotEmpty()
        )
    }

    @Test
    fun noEnabledSourceIsMissingItsRunner() = runBlocking {
        // The 9.2.3 symptom: a record with a version but no runner is filtered
        // out of every list, so the source silently vanishes. A runner must
        // exist for every source that claims to be serving.
        val orphans = index.getAll()
            .filter { it.status == PluginStatus.ENABLED || it.status == PluginStatus.INSTALLED }
            .filter { registry.pluginFor(it.id) == null }
        assertTrue(
            "ENABLED sources without a runner (they vanish from every list): $orphans",
            orphans.isEmpty()
        )
    }

    @Test
    fun everyRecordMapsToAVisibleRowState() = runBlocking {
        for (record in index.getAll()) {
            val unregistered = registry.pluginFor(record.id) == null
            val state = rowStateFor(record, unregistered)
            assertNotNull("row state for ${record.id} must resolve", state)
            // No state may be "hidden": SERVING and every badge state render a row.
            assertTrue(
                "${record.id} (${record.status}) must render a visible row, got $state",
                state in VISIBLE_STATES
            )
        }
    }

    @Test
    fun bootstrapIsIdempotentAndNeverMovesAPointerBackwards() = runBlocking {
        val before = index.getAll().associate { it.id to it.activeVersion }
        val outcomes = loader.bootstrap()
        assertTrue("bootstrap must return outcomes", outcomes.isNotEmpty())
        val after = index.getAll().associate { it.id to it.activeVersion }
        for ((id, versionBefore) in before) {
            val versionAfter = after[id]
            if (versionBefore == null || versionAfter == null) continue
            assertTrue(
                "$id moved backwards: v$versionBefore -> v$versionAfter (a bundled asset must " +
                    "never overwrite a newer synced version)",
                versionAfter >= versionBefore
            )
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 60_000L
        val VISIBLE_STATES = setOf(
            PluginRowState.SERVING,
            PluginRowState.UNRESOLVED,
            PluginRowState.HELD,
            PluginRowState.QUARANTINED,
            PluginRowState.INCOMPATIBLE,
            PluginRowState.AVAILABLE,
            PluginRowState.REVOKED
        )
    }
}
