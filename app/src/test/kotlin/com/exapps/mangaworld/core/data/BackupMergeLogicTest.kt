package com.exapps.mangaworld.core.data

import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-cluster (backup & restore) pure-logic tests. The managers' DataStore /
 * SharedPreferences / Room backends are untestable on JVM, so everything
 * asserted here goes through the internal merge/parse helpers; the thin
 * storage wrappers are covered by inspection + CI lint.
 */
class BackupMergeLogicTest {

    private val collections = CollectionManager(mockk(relaxed = true))
    private val bookmarks = BookmarkManager(mockk(relaxed = true))
    private val stats = ReadingStatsStore(mockk(relaxed = true))
    private val backup = LocalBackupManager(
        context = mockk(relaxed = true),
        database = mockk(relaxed = true),
        favoriteDao = mockk(relaxed = true),
        historyDao = mockk(relaxed = true),
        readChapterDao = mockk(relaxed = true),
        progressDao = mockk(relaxed = true),
        annotationDao = mockk(relaxed = true),
        readingStatsStore = mockk(relaxed = true),
        collectionManager = mockk(relaxed = true),
        bookmarkManager = mockk(relaxed = true),
        settingsRepository = mockk(relaxed = true),
        pluginIndex = mockk(relaxed = true)
    )

    // ─── BK-1 collections ─────────────────────────────────────────────────

    @Test
    fun mergeCollections_newestWinsKeepsOrderAndAppends() {
        val existing = listOf(
            MangaCollection("c1", "Old", mangaIds = listOf("m1"), updatedAt = 100L),
            MangaCollection("c2", "Keep", mangaIds = listOf("m2"), updatedAt = 100L)
        )
        val incoming = listOf(
            MangaCollection("c1", "New", mangaIds = listOf("m1", "m3"), updatedAt = 200L),
            MangaCollection("c3", "Added", mangaIds = listOf("m9"), updatedAt = 50L)
        )
        val merged = collections.mergeCollections(existing, incoming)
        assertEquals(listOf("c1", "c2", "c3"), merged.map { it.id })
        assertEquals("New", merged[0].name)
        assertEquals(listOf("m1", "m3"), merged[0].mangaIds)
        assertEquals("Keep", merged[1].name)
    }

    @Test
    fun mergeCollections_staleIncomingDoesNotClobber() {
        val existing = listOf(MangaCollection("c1", "Fresh", updatedAt = 500L))
        val merged = collections.mergeCollections(
            existing,
            listOf(MangaCollection("c1", "Stale", updatedAt = 100L))
        )
        assertEquals("Fresh", merged.single().name)
    }

    // ─── BK-1 bookmarks ───────────────────────────────────────────────────

    @Test
    fun mergeBookmarks_unionsByIdIncomingWins() {
        val existing = listOf(
            Bookmark("b1", "m1", "ch1", 3, "old note"),
            Bookmark("b2", "m1", "ch2", 0)
        )
        val incoming = listOf(
            Bookmark("b1", "m1", "ch1", 3, "edited note"),
            Bookmark("b3", "m1", "ch9", 1)
        )
        val merged = bookmarks.mergeBookmarks(existing, incoming)
        assertEquals(listOf("b1", "b2", "b3"), merged.map { it.id })
        assertEquals("edited note", merged[0].note)
    }

    // ─── BK-2 enabledSources guard ────────────────────────────────────────

    @Test
    fun effectiveEnabledSources_absentKeepsStored() {
        with(backup) {
            assertEquals(
                setOf("azora", "asq3"),
                JSONObject().effectiveEnabledSources(setOf("azora", "asq3"))
            )
        }
    }

    @Test
    fun effectiveEnabledSources_presentEmptyHonorsExplicitDisableAll() {
        with(backup) {
            val json = JSONObject().put("enabledSources", org.json.JSONArray())
            assertTrue(json.effectiveEnabledSources(setOf("azora")).isEmpty())
        }
    }

    @Test
    fun effectiveEnabledSources_presentValuesWin() {
        with(backup) {
            val json = JSONObject().put("enabledSources", org.json.JSONArray(listOf("asq3")))
            assertEquals(setOf("asq3"), json.effectiveEnabledSources(setOf("azora")))
        }
    }

    // ─── BK-4 stats merge ─────────────────────────────────────────────────

    @Test
    fun mergeIntMapJson_takesPerDateMax() {
        val merged = JSONObject(stats.mergeIntMapJson(
            """{"2026-09-10":5,"2026-09-11":3}""",
            """{"2026-09-11":7,"2026-09-12":2}"""
        ))
        assertEquals(5, merged.getInt("2026-09-10"))
        assertEquals(7, merged.getInt("2026-09-11"))
        assertEquals(2, merged.getInt("2026-09-12"))
    }

    @Test
    fun mergeLongMapJson_takesPerDateMaxAndToleratesGarbage() {
        val merged = JSONObject(stats.mergeLongMapJson("not-json", """{"2026-09-11":9000}"""))
        assertEquals(9000L, merged.getLong("2026-09-11"))
    }

    @Test
    fun mergeLastReadDate_picksLaterDate() {
        assertEquals("2026-09-12", stats.mergeLastReadDate("2026-09-10", "2026-09-12"))
        assertEquals("2026-09-10", stats.mergeLastReadDate("2026-09-10", ""))
        assertEquals("", stats.mergeLastReadDate("", ""))
    }
}
