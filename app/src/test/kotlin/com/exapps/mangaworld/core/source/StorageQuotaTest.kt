package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginStorage
import com.exapps.mangaworld.core.source.plugins.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: storage quota. Eviction is oldest-first among versions that are neither
 * active nor rollback-eligible — those two are never evicted, even over budget.
 */
class StorageQuotaTest {

    private val starz = SourceId("starz")
    private val hijala = SourceId("hijala")

    private fun v(id: SourceId, version: Int, bytes: Long) =
        PluginStorage.StoredVersion(id, version, bytes)

    @Test
    fun underBudgetEvictsNothing() {
        val stored = listOf(v(starz, 3, 100), v(starz, 4, 100))
        val plan = PluginStorage.selectEvictable(
            stored,
            activeVersions = mapOf(starz to 4),
            previousVersions = mapOf(starz to 3),
            budgetBytes = 500
        )
        assertTrue(plan.evict.isEmpty())
        assertEquals(false, plan.stillOverBudget)
    }

    @Test
    fun evictsOldestNonProtectedFirst() {
        val stored = listOf(
            v(starz, 2, 100),
            v(starz, 3, 100),
            v(starz, 4, 100),
            v(hijala, 1, 100)
        )
        val plan = PluginStorage.selectEvictable(
            stored,
            activeVersions = mapOf(starz to 4, hijala to 1),
            previousVersions = mapOf(starz to 3),
            budgetBytes = 300
        )
        // 400 total, budget 300 → evict v2 (100); only v2 is eligible.
        assertEquals(listOf(v(starz, 2, 100)), plan.evict)
        assertEquals(false, plan.stillOverBudget)
    }

    @Test
    fun activeAndPreviousNeverEvictedEvenOverBudget() {
        val stored = listOf(v(starz, 3, 300), v(starz, 4, 300))
        val plan = PluginStorage.selectEvictable(
            stored,
            activeVersions = mapOf(starz to 4),
            previousVersions = mapOf(starz to 3),
            budgetBytes = 100
        )
        assertTrue(plan.evict.isEmpty())
        assertEquals(true, plan.stillOverBudget)
    }
}
