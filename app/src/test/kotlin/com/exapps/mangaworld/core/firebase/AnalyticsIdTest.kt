package com.exapps.mangaworld.core.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Analytics-ID contract (#25): bounded but collision-free. The old bare
 * `takeLast(36)` merged distinct long-slug manga into one analytics row.
 */
class AnalyticsIdTest {

    @Test
    fun `short ids pass through untouched`() {
        assertEquals("azora_one-piece", analyticsMangaId("azora_one-piece"))
        assertEquals("x".repeat(36), analyticsMangaId("x".repeat(36)))
    }

    @Test
    fun `long ids stay bounded and readable`() {
        val id = analyticsMangaId("olympus_" + "a-very-long-slug-that-keeps-going".repeat(3))
        assertTrue(id.length <= 36)
        assertTrue(id.contains("_"))
    }

    @Test
    fun `same-tail ids no longer alias`() {
        val tail = "x".repeat(50)
        assertNotEquals(analyticsMangaId("source-a_$tail"), analyticsMangaId("source-b_$tail"))
    }

    @Test
    fun `mapping is deterministic`() {
        val id = "meshmanga_" + "s".repeat(60)
        assertEquals(analyticsMangaId(id), analyticsMangaId(id))
    }
}
