package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.ChapterLockedException
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.plugins.HealthState
import com.exapps.mangaworld.core.source.plugins.HealthStore
import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.SourceHealthMonitor
import com.exapps.mangaworld.core.source.plugins.SourceHealthPolicy
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.MangaItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 drift watch (§11B): staged thresholds, false-positive-safe counting
 * (search never counts; environment states never count), quarantine isolation
 * with builtin fallback, and re-smoke-gated recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceHealthTest {

    private class FakeHealth : HealthStore {
        val map = mutableMapOf<String, SourceHealthPolicy.Observation>()
        override suspend fun load(id: String) =
            map[id] ?: SourceHealthPolicy.initial()

        override suspend fun save(id: String, observation: SourceHealthPolicy.Observation) {
            map[id] = observation
        }
    }

    private class FakeIndex : PluginIndexStore {
        val map = mutableMapOf<String, PluginIndexRecord>()
        override suspend fun get(id: String) = map[id]
        override suspend fun getAll() = map.values.toList()
        override suspend fun put(record: PluginIndexRecord) {
            map[record.id] = record
        }
        override suspend fun remove(id: String) = map.remove(id) != null
    }

    private val io = kotlinx.coroutines.Dispatchers.Unconfined

    private fun monitor(
        health: FakeHealth = FakeHealth(),
        index: FakeIndex = FakeIndex(),
        ids: Array<String> = arrayOf("hijala", "lavascans")
    ) = SourceHealthMonitor(health, index, SourceUiTestFixtures.registry(*ids), io)

    private fun home(vararg titles: String) = Result.success(
        HomeData(
            featured = titles.map {
                MangaItem("h_$it", it, it, "", com.exapps.mangaworld.core.source.plugins.SourceId("hijala"))
            }
        )
    )

    private fun emptyHome() = Result.success(HomeData())

    private fun failure(e: Throwable = RuntimeException("boom")) = Result.failure<HomeData>(e)

    @Test
    fun policyThresholdsAndHysteresis() {
        var obs = SourceHealthPolicy.initial()
        // 1–4: counting, still OK.
        repeat(4) { i ->
            val d = SourceHealthPolicy.observe(obs, failed = true, emptyPrimary = false, nowMs = 1_000)
            assertTrue(d is SourceHealthPolicy.Decision.Hold)
            d as SourceHealthPolicy.Decision.Hold
            assertEquals(SourceHealthPolicy.HealthState.OK, d.state)
            obs = SourceHealthPolicy.Observation(d.anomalies, d.state, 0L)
        }
        // 5th: degraded badge, still serving.
        val warn = SourceHealthPolicy.observe(obs, failed = true, emptyPrimary = false, nowMs = 1_000)
        assertTrue(warn is SourceHealthPolicy.Decision.Hold)
        warn as SourceHealthPolicy.Decision.Hold
        assertEquals(SourceHealthPolicy.HealthState.DEGRADED, warn.state)
        // Success with content resets to zero (no flapping memory).
        assertTrue(
            SourceHealthPolicy.observe(
                SourceHealthPolicy.Observation(warn.anomalies, warn.state, 0L),
                failed = false, emptyPrimary = false, nowMs = 1_000
            ) is SourceHealthPolicy.Decision.Reset
        )
        // 10th consecutive: quarantine.
        var o = SourceHealthPolicy.initial()
        repeat(9) {
            val d = SourceHealthPolicy.observe(o, failed = true, emptyPrimary = false, nowMs = 1_000)
            o = SourceHealthPolicy.Observation(
                (d as SourceHealthPolicy.Decision.Hold).anomalies, d.state, 0L
            )
        }
        val q = SourceHealthPolicy.observe(o, failed = true, emptyPrimary = false, nowMs = 1_000)
        assertTrue(q is SourceHealthPolicy.Decision.Quarantine)
    }

    @Test
    fun quarantineCooldownSuppressesChurn() {
        val obs = SourceHealthPolicy.Observation(10, SourceHealthPolicy.HealthState.DEGRADED, 1_000)
        // 10 anomalies but quarantined 1s ago → hold degraded, not re-quarantine.
        val d = SourceHealthPolicy.observe(obs, failed = true, emptyPrimary = false, nowMs = 2_000)
        assertTrue(d is SourceHealthPolicy.Decision.Hold)
        // Past cooldown → quarantine again.
        val d2 = SourceHealthPolicy.observe(
            obs, failed = true, emptyPrimary = false,
            nowMs = 1_000 + SourceHealthPolicy.QUARANTINE_COOLDOWN_MS + 1
        )
        assertTrue(d2 is SourceHealthPolicy.Decision.Quarantine)
    }

    @Test
    fun quarantinedHoldsWithoutResmoke() {
        val obs = SourceHealthPolicy.Observation(10, SourceHealthPolicy.HealthState.QUARANTINED, 1_000)
        // Even clean traffic cannot self-clear quarantine.
        val d = SourceHealthPolicy.observe(obs, failed = false, emptyPrimary = false, nowMs = 5_000)
        assertTrue(d is SourceHealthPolicy.Decision.Hold)
        assertEquals(
            SourceHealthPolicy.HealthState.QUARANTINED,
            (d as SourceHealthPolicy.Decision.Hold).state
        )
    }

    @Test
    fun monitorDegradesThenQuarantinesOverride() = runTest {
        val health = FakeHealth()
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 2, 1, PluginOrigin.OFFICIAL, PluginStatus.ENABLED, null))
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans")
        // Simulate an override registration (as sync would install it).
        val scraper: com.exapps.mangaworld.core.data.remote.scraper.MangaScraper =
            io.mockk.mockk(relaxed = true)
        val override = object : com.exapps.mangaworld.core.source.plugins.SourcePlugin {
            override val descriptor = registry.descriptorFor("hijala")!!
            override val display = com.exapps.mangaworld.core.source.plugins.SourceDisplay(0, 0)
            override val scraper = scraper
        }
        registry.registerVerified(override, PluginOrigin.OFFICIAL)
        assertTrue(registry.isOverridden("hijala"))

        val m = SourceHealthMonitor(health, index, registry, io)
        repeat(4) { m.observeHome("hijala", failure()) }
        assertEquals(HealthState.OK, m.snapshot("hijala"))
        m.observeHome("hijala", failure())
        assertEquals(HealthState.DEGRADED, m.snapshot("hijala"))
        // Still serving while degraded.
        assertTrue(registry.isOverridden("hijala"))
        repeat(5) { m.observeHome("hijala", failure()) }
        assertEquals(HealthState.QUARANTINED, m.snapshot("hijala"))
        // Isolation: override cleared (builtin resumes), record quarantined.
        assertTrue(!registry.isOverridden("hijala"))
        assertEquals(PluginStatus.QUARANTINED, index.get("hijala")!!.status)
    }

    @Test
    fun builtinWithoutRecordOnlyRecords() = runTest {
        val m = monitor()
        repeat(12) { m.observeHome("hijala", failure()) }
        // No index record → no fallback exists → keep serving, record degraded.
        assertEquals(HealthState.QUARANTINED, m.snapshot("hijala"))
    }

    @Test
    fun environmentFailuresNeverCount() = runTest {
        val m = monitor()
        repeat(8) {
            m.observeHome("hijala", failure(CloudflareChallengeException("d", "u")))
            m.observeDetail("hijala", failure(ChapterLockedException()))
            m.observePages("hijala", failure(kotlinx.coroutines.CancellationException()))
        }
        assertEquals(HealthState.OK, m.snapshot("hijala"))
    }

    @Test
    fun emptyHomeCountsEmptyDetailDoesNot() = runTest {
        val m = monitor()
        repeat(5) { m.observeHome("hijala", emptyHome()) }
        assertEquals(HealthState.DEGRADED, m.snapshot("hijala"))
        // Detail success (even chapter-less) is not drift — title parsed, site up.
        val m2 = monitor()
        repeat(9) {
            m2.observeDetail(
                "hijala",
                Result.success(
                    com.exapps.mangaworld.domain.model.MangaDetail(
                        id = "d", slug = "d", title = "T", coverUrl = "",
                        source = com.exapps.mangaworld.core.source.plugins.SourceId("hijala")
                    )
                )
            )
        }
        assertEquals(HealthState.OK, m2.snapshot("hijala"))
    }

    @Test
    fun successResetsCounter() = runTest {
        val m = monitor()
        repeat(4) { m.observeHome("hijala", failure()) }
        m.observeHome("hijala", home("A"))
        repeat(4) { m.observeHome("hijala", failure()) }
        // 4 + reset + 4: never reached the 5-bar.
        assertEquals(HealthState.OK, m.snapshot("hijala"))
    }

    @Test
    fun reverifyResetsAfterCleanSmoke() = runTest {
        val health = FakeHealth()
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 2, 1, PluginOrigin.OFFICIAL, PluginStatus.QUARANTINED, null))
        health.save(
            "hijala",
            SourceHealthPolicy.Observation(10, HealthState.QUARANTINED, 1_000)
        )
        val scraper: MangaScraper = mockk()
        coEvery { scraper.getHomeData() } returns home("A")
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans", scrapers = mapOf("hijala" to scraper))
        val m = SourceHealthMonitor(health, index, registry, io)
        assertEquals(true, m.reverify("hijala"))
        assertEquals(HealthState.OK, m.snapshot("hijala"))
        assertEquals(PluginStatus.INSTALLED, index.get("hijala")!!.status)
    }

    @Test
    fun reverifyFailsOnDirtySmoke() = runTest {
        val health = FakeHealth()
        val index = FakeIndex()
        index.put(PluginIndexRecord("hijala", 2, 1, PluginOrigin.OFFICIAL, PluginStatus.QUARANTINED, null))
        health.save(
            "hijala",
            SourceHealthPolicy.Observation(10, HealthState.QUARANTINED, 1_000)
        )
        val scraper: MangaScraper = mockk()
        coEvery { scraper.getHomeData() } returns emptyHome()
        val registry = SourceUiTestFixtures.registry("hijala", "lavascans", scrapers = mapOf("hijala" to scraper))
        val m = SourceHealthMonitor(health, index, registry, io)
        assertEquals(false, m.reverify("hijala"))
        assertEquals(HealthState.QUARANTINED, m.snapshot("hijala"))
        assertEquals(PluginStatus.QUARANTINED, index.get("hijala")!!.status)
    }
}
