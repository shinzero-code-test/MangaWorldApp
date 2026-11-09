package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.local.SyncTombstone
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadChapterEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.core.firebase.computeLocalPushFingerprint
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.model.ReaderImageFilter
import com.exapps.mangaworld.domain.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * U-27(c) cached-diff watermark: identical push inputs must collide, any
 * pushed-field change must differ — otherwise the skip could drop a real
 * sync. Pure function (no Firestore), so this runs as a JVM unit test.
 */
class LocalPushFingerprintTest {

    private fun fingerprint(
        uid: String = "uid-1",
        favorites: List<FavoriteEntity> = listOf(
            FavoriteEntity(mangaId = "m1", slug = "s1", title = "t", coverUrl = "", sourceId = "azora", addedAt = 100L),
        ),
        history: List<ReadingHistoryEntity> = listOf(
            ReadingHistoryEntity(mangaId = "m1", slug = "s1", title = "t", coverUrl = "", sourceId = "azora", lastChapterNumber = 3f, lastReadAt = 200L),
        ),
        annotations: List<ReaderAnnotationEntity> = emptyList(),
        readMarks: List<ReadChapterEntity> = emptyList(),
        tombstones: List<SyncTombstone> = emptyList(),
        settings: AppSettings = AppSettings(),
        reader: ReaderSettings = ReaderSettings(),
        storedEnabledSources: Set<String> = AppSettings().enabledSources,
        totalReadingTimeMs: Long = 5_000L,
        totalMangaRead: Int = 2,
        achievements: Map<String, Any> = emptyMap(),
    ) = computeLocalPushFingerprint(
        uid, favorites, history, annotations, readMarks, tombstones,
        settings, reader, storedEnabledSources, totalReadingTimeMs, totalMangaRead, achievements,
    )

    @Test
    fun identicalInputs_collide() {
        assertEquals(fingerprint(), fingerprint())
        // Row ORDER must not matter (Room ORDER BY <ts> ties can shuffle).
        val a = FavoriteEntity(mangaId = "m1", slug = "s", title = "t", coverUrl = "", sourceId = "azora", addedAt = 1L)
        val b = FavoriteEntity(mangaId = "m2", slug = "s", title = "t", coverUrl = "", sourceId = "azora", addedAt = 2L)
        assertEquals(fingerprint(favorites = listOf(a, b)), fingerprint(favorites = listOf(b, a)))
    }

    @Test
    fun changedEntityTimestamp_differs() {
        val changed = listOf(
            FavoriteEntity(mangaId = "m1", slug = "s1", title = "t", coverUrl = "", sourceId = "azora", addedAt = 101L),
        )
        assertNotEquals(fingerprint(), fingerprint(favorites = changed))
    }

    @Test
    fun changedSettingsField_differs() {
        assertNotEquals(
            fingerprint(),
            fingerprint(settings = AppSettings(theme = AppTheme.LIGHT)),
        )
    }

    @Test
    fun changedReaderFilter_differs() {
        assertNotEquals(
            fingerprint(),
            fingerprint(reader = ReaderSettings(imageFilter = ReaderImageFilter.SMART_CROP)),
        )
    }

    @Test
    fun changedUid_orTombstone_orAchievements_differ() {
        assertNotEquals(fingerprint(), fingerprint(uid = "uid-2"))
        assertNotEquals(
            fingerprint(),
            fingerprint(tombstones = listOf(SyncTombstone("favorites", "m1", 99L))),
        )
        assertNotEquals(
            fingerprint(),
            fingerprint(achievements = mapOf("speedreader" to true)),
        )
    }
}
