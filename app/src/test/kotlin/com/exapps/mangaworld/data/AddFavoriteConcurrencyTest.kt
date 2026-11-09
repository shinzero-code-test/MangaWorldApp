package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.LibraryRepositoryImpl
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.FavoriteManga
import com.exapps.mangaworld.core.source.plugins.SourceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U-20: addFavorite on an existing row must use the targeted UPDATE
 * (setFavoriteIfExisting) — never a whole-entity REPLACE that clobbers
 * concurrent syncFavoriteProgress counters.
 */
class AddFavoriteConcurrencyTest {

    @Test
    fun addFavoriteOnExistingRowUsesTargetedUpdate() = runTest {
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val existing = FavoriteEntity(
            mangaId = "m1", slug = "s1", title = "Title", coverUrl = "", sourceId = "azora",
            readChapters = 7, totalChapters = 20
        )
        coEvery { favoriteDao.getById("m1") } returns existing
        coEvery { favoriteDao.setFavoriteIfExisting("m1", any()) } returns 1

        val prefs = mockk<AppPreferences>(relaxed = true)
        val repo = LibraryRepositoryImpl(
            favoriteDao = favoriteDao,
            historyDao = mockk<ReadingHistoryDao>(relaxed = true),
            readChapterDao = mockk<ReadChapterDao>(relaxed = true),
            progressDao = mockk<ReadingProgressDao>(relaxed = true),
            readerAnnotationDao = mockk<ReaderAnnotationDao>(relaxed = true),
            prefs = prefs,
            sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
        )

        repo.addFavorite(
            FavoriteManga(
                mangaId = "m1", slug = "s1", title = "Title", coverUrl = "",
                source = SourceId("azora"), totalChapters = 20,
                readChapters = 0
            )
        )

        // Targeted path: progress counters untouched (no insert of stale copy).
        coVerify { favoriteDao.setFavoriteIfExisting("m1", any()) }
        coVerify(exactly = 0) { favoriteDao.insert(any()) }
        coVerify { prefs.clearSyncTombstone("favorites", "m1") }
    }

    @Test
    fun addFavoriteOnMissingRowInserts() = runTest {
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        coEvery { favoriteDao.getById("new") } returns null
        coEvery { favoriteDao.insert(any()) } returns Unit

        val repo = LibraryRepositoryImpl(
            favoriteDao = favoriteDao,
            historyDao = mockk<ReadingHistoryDao>(relaxed = true),
            readChapterDao = mockk<ReadChapterDao>(relaxed = true),
            progressDao = mockk<ReadingProgressDao>(relaxed = true),
            readerAnnotationDao = mockk<ReaderAnnotationDao>(relaxed = true),
            prefs = mockk<AppPreferences>(relaxed = true),
            sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
        )

        repo.addFavorite(
            FavoriteManga(
                mangaId = "new", slug = "s", title = "T", coverUrl = "",
                source = SourceId("azora")
            )
        )

        coVerify { favoriteDao.insert(any()) }
        coVerify(exactly = 0) { favoriteDao.setFavoriteIfExisting(any(), any()) }
    }
}
