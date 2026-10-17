package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginRowState
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.SourceDisplayResolver
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.source.plugins.SourceUiMapper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.2.3: plugin rows are OBSERVABLE.
 *
 * The fleet report was "tap refresh N times until the source shows up, then it
 * vanishes after restarting the app, and it never reaches Home/Settings". Root
 * cause: every screen read the mapper's snapshot once, while the registry and
 * the index changed asynchronously (boot resume, sweep, approval) — so list
 * membership was a race between "screen built its list" and "plugin registered".
 *
 * These tests pin the fix at the two seams that made it possible:
 * - registry mutations are observable ([SourceRegistry.revision]),
 * - index writes are observable ([PluginIndexStore.changes]) and the mapper
 *   re-reads itself, so one-shot readers (`entry(id)` — the traffic gate) see
 *   the same world as stream readers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceRowsReactiveTest {

    /** Index fake WITH the change signal (production has one; plain fakes don't). */
    private class SignalingIndex(
        val map: MutableMap<String, PluginIndexRecord> = mutableMapOf()
    ) : PluginIndexStore {
        private val writes = MutableSharedFlow<Unit>(
            replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val changes = writes
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
            writes.tryEmit(Unit)
        }

        override suspend fun remove(id: String): Boolean {
            val existed = map.remove(id) != null
            if (existed) writes.tryEmit(Unit)
            return existed
        }
    }

    private fun context(): android.content.Context = mockk {
        every { getString(any<Int>()) } returns "?"
    }

    private fun previewJson(id: String, name: String) =
        """{"id":"$id","version":1,"engine":"script","baseUrl":"https://$id.example",""" +
            """"names":{"ar":"$name","en":"$name"},"allowedHosts":["$id.example"]}"""

    // ── registry seam ──────────────────────────────────────────────────────

    @Test
    fun registryRevisionMovesOnlyWhenMembershipChanges() {
        val registry = SourceUiTestFixtures.registry("azora")
        val before = registry.revision.value

        val plugin = registry.pluginFor("azora")!!
        val outcome = registry.registerVerified(plugin, PluginOrigin.OFFICIAL)
        assertTrue(outcome is SourceRegistry.RegisterOutcome.Superseded)
        assertTrue(registry.revision.value > before)

        val afterSupersede = registry.revision.value
        // Refused (custom/local shadowing a builtin) changes nothing.
        val refused = registry.registerVerified(plugin, PluginOrigin.CUSTOM)
        assertTrue(refused is SourceRegistry.RegisterOutcome.Refused)
        assertEquals(afterSupersede, registry.revision.value)

        // Missing id: removal is a no-op and must not wake observers.
        assertFalse(registry.removeAll("nope"))
        assertEquals(afterSupersede, registry.revision.value)

        assertTrue(registry.removeAll("azora"))
        assertTrue(registry.revision.value > afterSupersede)
    }

    // ── rows follow late registration (the "tap refresh N times" bug) ───────

    @Test
    fun rowsAppearWhenRegistryRegistersAfterCollectionStarted() = runTest {
        val registry = SourceUiTestFixtures.registry("azora")
        val mapper = SourceUiMapper(
            registry = registry,
            resolver = SourceDisplayResolver(context()),
            indexStore = SignalingIndex(),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        val seen = mutableListOf<List<String>>()
        val job = launch { mapper.entriesFlow().collect { seen += it.map { e -> e.id } } }
        runCurrent()
        assertEquals(listOf("azora"), seen.last())

        // A sweep/boot-resume registers a new source AFTER the flow started —
        // no explicit refresh, no re-subscribe, no re-tapping anything.
        registry.registerVerified(
            SourceUiTestFixtures.newPlugin("manonga"),
            PluginOrigin.OFFICIAL
        )
        runCurrent()
        job.cancel()

        assertEquals("rows must include the late registration", listOf("azora", "manonga"), seen.last().sorted())
    }

    @Test
    fun entryFlowResolvesOnceTheRowAppears() = runTest {
        val registry = SourceUiTestFixtures.registry("azora")
        val mapper = SourceUiMapper(
            registry = registry,
            resolver = SourceDisplayResolver(context()),
            indexStore = SignalingIndex(),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        assertNull(mapper.entryFlow("manonga").first())
        registry.registerVerified(SourceUiTestFixtures.newPlugin("manonga"), PluginOrigin.OFFICIAL)
        assertEquals("manonga", mapper.entryFlow("manonga").first()?.id)
    }

    // ── index seam: badges/holds reach every screen ─────────────────────────

    @Test
    fun heldRowAppearsOnIndexWriteWithoutExplicitRefresh() = runTest {
        val index = SignalingIndex()
        val mapper = SourceUiMapper(
            registry = SourceUiTestFixtures.registry("azora"),
            resolver = SourceDisplayResolver(context()),
            indexStore = index,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        assertTrue(mapper.entries().none { it.id == "gated" })

        // The consent hold lands in the index; the mapper must notice by itself.
        index.put(
            PluginIndexRecord(
                id = "gated", activeVersion = 2, previousVersion = null,
                origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
                manifestJson = previewJson("gated", "Gated")
            )
        )
        runCurrent()

        val held = mapper.entries().single { it.id == "gated" }
        assertEquals(PluginRowState.HELD, held.rowState)
        assertNotNull("held rows expose their consent facts", mapper.heldDetails()["gated"])
        // One-shot readers (the traffic gate in MangaDetailViewModel) see the
        // same snapshot — no screen-specific re-read required.
        assertEquals(PluginRowState.HELD, mapper.entry("gated")?.rowState)
    }

    @Test
    fun quiescentBootstrapStillSeedsTheSnapshot() = runTest {
        // A fresh install writes nothing after boot: the mapper still seeds
        // itself once (onStart), so rows exist without any screen refresh.
        val index = SignalingIndex()
        index.map["quiet"] = PluginIndexRecord(
            id = "quiet", activeVersion = 1, previousVersion = null,
            origin = PluginOrigin.OFFICIAL, status = PluginStatus.QUARANTINED,
            manifestJson = previewJson("quiet", "Quiet")
        )
        val mapper = SourceUiMapper(
            registry = SourceUiTestFixtures.registry("azora"),
            resolver = SourceDisplayResolver(context()),
            indexStore = index,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        assertEquals(
            PluginRowState.QUARANTINED,
            mapper.entries().single { it.id == "quiet" }.rowState
        )
    }

    // ── registry rows shadow held extras (no duplicate ids) ─────────────────

    @Test
    fun registeredRowSupersedesItsHeldExtra() = runTest {
        val index = SignalingIndex()
        index.map["dupe"] = PluginIndexRecord(
            id = "dupe", activeVersion = 1, previousVersion = null,
            origin = PluginOrigin.OFFICIAL, status = PluginStatus.DISABLED,
            manifestJson = previewJson("dupe", "Dupe")
        )
        val registry = SourceUiTestFixtures.registry("azora")
        val mapper = SourceUiMapper(
            registry = registry,
            resolver = SourceDisplayResolver(context()),
            indexStore = index,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        registry.registerVerified(SourceUiTestFixtures.newPlugin("dupe"), PluginOrigin.OFFICIAL)
        runCurrent()
        assertEquals(1, mapper.entries().count { it.id == "dupe" })
    }
}