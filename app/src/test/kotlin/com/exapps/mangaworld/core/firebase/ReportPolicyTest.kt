package com.exapps.mangaworld.core.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report flood-quota policy (#2): dedupe IDs are deterministic per
 * reporter × type × target, distinct across any dimension change, and
 * Firestore-safe (no slashes — usable as document IDs).
 */
class ReportPolicyTest {

    @Test
    fun `dedupe id is deterministic`() {
        assertEquals(
            reportDedupeId("uid1", "comment", "c9"),
            reportDedupeId("uid1", "comment", "c9")
        )
    }

    @Test
    fun `dedupe id separates every dimension`() {
        val base = reportDedupeId("uid1", "comment", "c9")
        assertNotEquals(base, reportDedupeId("uid2", "comment", "c9"))
        assertNotEquals(base, reportDedupeId("uid1", "review", "c9"))
        assertNotEquals(base, reportDedupeId("uid1", "comment", "c10"))
    }

    @Test
    fun `dedupe id is a safe document id`() {
        val id = reportDedupeId("uid/odd", "comment", "c/9")
        assertTrue(id.none { it == '/' })
        assertTrue(id.startsWith("r_"))
    }

    @Test
    fun `quota constants are sane`() {
        assertEquals(10, MAX_REPORTS_PER_HOUR)
        assertEquals(3_600_000L, REPORT_WINDOW_MS)
    }
}
