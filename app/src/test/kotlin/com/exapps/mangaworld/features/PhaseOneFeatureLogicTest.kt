package com.exapps.mangaworld.features

import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.utils.filterLatestUpdates
import com.exapps.mangaworld.presentation.utils.formatDiagnosticBytes
import com.exapps.mangaworld.presentation.utils.normalizeBlacklistInput
import com.exapps.mangaworld.presentation.utils.shouldTriggerSmartPrefetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseOneFeatureLogicTest {

    @Test
    fun smartPrefetchTriggersPastHalfway() {
        assertFalse(shouldTriggerSmartPrefetch(currentPage = 2, totalPages = 10))
        assertTrue(shouldTriggerSmartPrefetch(currentPage = 5, totalPages = 10))
        assertTrue(shouldTriggerSmartPrefetch(currentPage = 1, totalPages = 1))
    }

    @Test
    fun latestUpdatesFilterRespectsSourceAndUnreadFlags() {
        val olympus = LatestChapterItem(
            mangaId = "olympus_solo",
            mangaSlug = "solo-leveling",
            mangaTitle = "Solo Leveling",
            coverUrl = "",
            chapterNumber = 1f,
            chapterUrl = "https://olympustaff.com/series/solo-leveling/1",
            timeAgo = "1h",
            source = MangaSource.OLYMPUS
        )
        val starz = olympus.copy(
            mangaId = "starz_solo",
            source = MangaSource.STARZ,
            chapterUrl = "https://manga-starz.net/manga/solo/1"
        )

        val unreadFiltered = filterLatestUpdates(
            items = listOf(olympus, starz),
            selectedSource = MangaSource.OLYMPUS,
            unreadOnly = true,
            readStates = mapOf(olympus.chapterUrl to false, starz.chapterUrl to true)
        )

        assertEquals(1, unreadFiltered.size)
        assertEquals(MangaSource.OLYMPUS, unreadFiltered.first().source)
    }

    @Test
    fun blacklistInputNormalizesLines() {
        val values = normalizeBlacklistInput("  solo\n\n tower of god \n ")
        assertEquals(setOf("solo", "tower of god"), values)
    }

    @Test
    fun diagnosticsFormattingUsesReadableUnits() {
        assertEquals("512 KB", formatDiagnosticBytes(512L * 1024L))
        assertEquals("2.0 MB", formatDiagnosticBytes(2L * 1024L * 1024L))
    }
}
