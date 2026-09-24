package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.LibraryRepositoryImpl
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dead-source grace: rows stored under a removed plugin id (rockmanga) must be hidden
 * from library surfaces — never resurrected under another source via the AZORA fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeadSourceFilteringTest {

    private fun favorite(id: String, sourceId: String) = FavoriteEntity(
        mangaId = id, slug = "s", title = "T-$id", coverUrl = "", sourceId = sourceId
    )

    private fun history(id: String, sourceId: String) = ReadingHistoryEntity(
        mangaId = id, slug = "s", title = "T-$id", coverUrl = "",
        sourceId = sourceId, lastChapterNumber = 1f, lastReadAt = 1L
    )

    private fun repoWith(
        favorites: List<FavoriteEntity>,
        history: List<ReadingHistoryEntity>
    ): LibraryRepositoryImpl {
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val historyDao = mockk<ReadingHistoryDao>(relaxed = true)
        every { favoriteDao.getAllFavorites() } returns flowOf(favorites)
        coEvery { favoriteDao.getByStatus(any()) } returns favorites
        every { historyDao.getAllHistory() } returns flowOf(history)
        return LibraryRepositoryImpl(
            favoriteDao = favoriteDao,
            historyDao = historyDao,
            readChapterDao = mockk(relaxed = true),
            progressDao = mockk(relaxed = true),
            readerAnnotationDao = mockk(relaxed = true),
            prefs = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true)
        )
    }

    @Test
    fun favoritesHideDeadSources() = runTest {
        val repo = repoWith(
            favorites = listOf(
                favorite("azora_x", "azora"),
                favorite("rockmanga_y", "rockmanga"),
                favorite("bogus_z", "nope")
            ),
            history = emptyList()
        )
        val ids = repo.getFavorites().first().map { it.mangaId }
        assertEquals(listOf("azora_x"), ids)
    }

    @Test
    fun favoritesByStatusHideDeadSources() = runTest {
        val repo = repoWith(
            favorites = listOf(favorite("a", "azora"), favorite("r", "rockmanga")),
            history = emptyList()
        )
        val ids = repo.getFavoritesByStatus("reading").map { it.mangaId }
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun historyHidesDeadSources() = runTest {
        val repo = repoWith(
            favorites = emptyList(),
            history = listOf(history("h1", "hijala"), history("h2", "rockmanga"))
        )
        val ids = repo.getReadingHistory().first().map { it.mangaId }
        assertEquals(listOf("h1"), ids)
    }

    @Test
    fun emptyWhenOnlyDeadRows() = runTest {
        val repo = repoWith(
            favorites = listOf(favorite("r", "rockmanga")),
            history = emptyList()
        )
        assertTrue(repo.getFavorites().first().isEmpty())
    }
}
