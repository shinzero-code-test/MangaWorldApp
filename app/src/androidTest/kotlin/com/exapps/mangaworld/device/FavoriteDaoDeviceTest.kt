package com.exapps.mangaworld.device

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Room invariant checks (Firebase Test Lab): the favourites
 * soft-delete contract — `removeFavorite` flips `isFavorite`, never deletes
 * the row — must hold against real SQLite, not just the JVM.
 */
@RunWith(AndroidJUnit4::class)
class FavoriteDaoDeviceTest {

    private lateinit var db: MangaDatabase

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insert_isVisibleInFavorites() = runTest {
        val dao = db.favoriteDao()
        dao.insert(
            FavoriteEntity(
                mangaId = "azora_solo",
                slug = "solo-leveling",
                title = "Solo Leveling",
                coverUrl = "",
                sourceId = "azora"
            )
        )
        val all = dao.getFavoritesList()
        assertEquals(1, all.size)
        assertEquals("azora_solo", all.first().mangaId)
    }

    @Test
    fun softRemove_hidesButKeepsRow() = runTest {
        val dao = db.favoriteDao()
        dao.insert(
            FavoriteEntity(
                mangaId = "olympus_solo",
                slug = "solo",
                title = "Solo",
                coverUrl = "",
                sourceId = "olympus"
            )
        )
        dao.setFavorite("olympus_solo", false, System.currentTimeMillis())
        // Hidden from the favourites list...
        assertTrue(dao.getFavoritesList().none { it.mangaId == "olympus_solo" })
        // ...but the row (and its reading progress) survives.
        assertTrue(dao.getAllLibraryEntries().any { it.mangaId == "olympus_solo" })
    }
}
