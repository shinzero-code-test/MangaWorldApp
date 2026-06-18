package com.exapps.mangaworld.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTestSuite {
    private lateinit var db: MangaDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ─── FavoriteDao ──────────────────────────────────────────────────────

    @Test
    fun favoriteDao_insertAndGetAll() = runTest {
        val dao = db.favoriteDao()
        dao.insert(testFavorite("m1", "Slug 1", "Manga 1"))
        dao.insert(testFavorite("m2", "Slug 2", "Manga 2"))
        val all = dao.getFavoritesList()
        assertEquals(2, all.size)
    }

    @Test
    fun favoriteDao_isFavorite_returnsTrueAfterInsert() = runTest {
        val dao = db.favoriteDao()
        dao.insert(testFavorite("m1"))
        assertTrue(dao.isFavorite("m1"))
        assertFalse(dao.isFavorite("m999"))
    }

    @Test
    fun favoriteDao_isFavoriteFlow_emitsCorrectValue() = runTest {
        val dao = db.favoriteDao()
        assertFalse(dao.isFavoriteFlow("m1").first())
        dao.insert(testFavorite("m1"))
        assertTrue(dao.isFavoriteFlow("m1").first())
    }

    @Test
    fun favoriteDao_delete_removesFavorite() = runTest {
        val dao = db.favoriteDao()
        dao.insert(testFavorite("m1"))
        assertTrue(dao.isFavorite("m1"))
        dao.delete("m1")
        assertFalse(dao.isFavorite("m1"))
    }

    @Test
    fun favoriteDao_updateProgress_updatesCounts() = runTest {
        val dao = db.favoriteDao()
        dao.insert(testFavorite("m1"))
        dao.updateProgress("m1", read = 5, total = 10)
        val fav = dao.getById("m1")
        assertEquals(5, fav?.readChapters)
        assertEquals(10, fav?.totalChapters)
    }

    @Test
    fun favoriteDao_insertReplace_updatesExisting() = runTest {
        val dao = db.favoriteDao()
        dao.insert(testFavorite("m1", title = "Old Title"))
        dao.insert(testFavorite("m1", title = "New Title"))
        val fav = dao.getById("m1")
        assertEquals("New Title", fav?.title)
    }

    // ─── ReadingHistoryDao ────────────────────────────────────────────────

    @Test
    fun historyDao_insertOrUpdate_andGetAll() = runTest {
        val dao = db.readingHistoryDao()
        dao.insertOrUpdate(testHistory("m1", lastReadAt = 100L))
        dao.insertOrUpdate(testHistory("m2", lastReadAt = 200L))
        val all = dao.getAll()
        assertEquals(2, all.size)
        assertEquals(200L, all[0].lastReadAt) // Descending order
    }

    @Test
    fun historyDao_getRecent_respectsLimit() = runTest {
        val dao = db.readingHistoryDao()
        for (i in 1..10) {
            dao.insertOrUpdate(testHistory("m$i", lastReadAt = i * 100L))
        }
        val recent = dao.getRecent(3)
        assertEquals(3, recent.size)
        assertEquals(1000L, recent[0].lastReadAt)
    }

    @Test
    fun historyDao_getLatest_returnsMostRecent() = runTest {
        val dao = db.readingHistoryDao()
        dao.insertOrUpdate(testHistory("m1", lastReadAt = 100L))
        dao.insertOrUpdate(testHistory("m2", lastReadAt = 500L))
        val latest = dao.getLatest()
        assertEquals("m2", latest?.mangaId)
    }

    @Test
    fun historyDao_clearAll_removesAll() = runTest {
        val dao = db.readingHistoryDao()
        dao.insertOrUpdate(testHistory("m1"))
        dao.insertOrUpdate(testHistory("m2"))
        dao.clearAll()
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun historyDao_delete_removesByMangaId() = runTest {
        val dao = db.readingHistoryDao()
        dao.insertOrUpdate(testHistory("m1"))
        dao.insertOrUpdate(testHistory("m2"))
        dao.delete("m1")
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("m2", all[0].mangaId)
    }

    // ─── ReadChapterDao ───────────────────────────────────────────────────

    @Test
    fun readChapterDao_markRead_andIsRead() = runTest {
        val dao = db.readChapterDao()
        dao.markRead(ReadChapterEntity("m1", 1.0f))
        assertTrue(dao.isRead("m1", 1.0f))
        assertFalse(dao.isRead("m1", 2.0f))
    }

    @Test
    fun readChapterDao_markUnread_removesEntry() = runTest {
        val dao = db.readChapterDao()
        dao.markRead(ReadChapterEntity("m1", 1.0f))
        dao.markUnread("m1", 1.0f)
        assertFalse(dao.isRead("m1", 1.0f))
    }

    @Test
    fun readChapterDao_getTotalReadCount() = runTest {
        val dao = db.readChapterDao()
        dao.markRead(ReadChapterEntity("m1", 1.0f))
        dao.markRead(ReadChapterEntity("m1", 2.0f))
        dao.markRead(ReadChapterEntity("m2", 1.0f))
        assertEquals(3, dao.getTotalReadCount())
    }

    @Test
    fun readChapterDao_getReadChapters_emitsFlow() = runTest {
        val dao = db.readChapterDao()
        dao.markRead(ReadChapterEntity("m1", 1.0f))
        dao.markRead(ReadChapterEntity("m1", 3.0f))
        val chapters = dao.getReadChapters("m1").first()
        assertEquals(setOf(1.0f, 3.0f), chapters.toSet())
    }

    @Test
    fun readChapterDao_getReadTimestamps_respectsLimit() = runTest {
        val dao = db.readChapterDao()
        for (i in 1..10) {
            dao.markRead(ReadChapterEntity("m1", i.toFloat(), readAt = i * 1000L))
        }
        val timestamps = dao.getReadTimestamps(limit = 3)
        assertEquals(3, timestamps.size)
    }

    // ─── ReadingProgressDao ───────────────────────────────────────────────

    @Test
    fun progressDao_saveAndGet() = runTest {
        val dao = db.readingProgressDao()
        dao.save(ReadingProgressEntity("m1", 1.0f, currentPage = 5, totalPages = 20))
        val progress = dao.get("m1", 1.0f)
        assertNotNull(progress)
        assertEquals(5, progress?.currentPage)
        assertEquals(20, progress?.totalPages)
    }

    @Test
    fun progressDao_getAllForManga() = runTest {
        val dao = db.readingProgressDao()
        dao.save(ReadingProgressEntity("m1", 1.0f, 5, 20))
        dao.save(ReadingProgressEntity("m1", 2.0f, 10, 20))
        dao.save(ReadingProgressEntity("m2", 1.0f, 3, 15))
        val all = dao.getAllForManga("m1")
        assertEquals(2, all.size)
    }

    @Test
    fun progressDao_saveReplace_updatesExisting() = runTest {
        val dao = db.readingProgressDao()
        dao.save(ReadingProgressEntity("m1", 1.0f, 5, 20))
        dao.save(ReadingProgressEntity("m1", 1.0f, 10, 20))
        val progress = dao.get("m1", 1.0f)
        assertEquals(10, progress?.currentPage)
    }

    // ─── MangaCacheDao ────────────────────────────────────────────────────

    @Test
    fun cacheDao_insertAndGet() = runTest {
        val dao = db.mangaCacheDao()
        dao.insert(testCache("m1"))
        val cached = dao.get("m1")
        assertNotNull(cached)
        assertEquals("Manga 1", cached?.title)
    }

    @Test
    fun cacheDao_evictOlderThan() = runTest {
        val dao = db.mangaCacheDao()
        dao.insert(testCache("m1", cachedAt = 100L))
        dao.insert(testCache("m2", cachedAt = 500L))
        dao.evictOlderThan(300L)
        assertNull(dao.get("m1"))
        assertNotNull(dao.get("m2"))
    }

    @Test
    fun cacheDao_getByIds() = runTest {
        val dao = db.mangaCacheDao()
        dao.insert(testCache("m1"))
        dao.insert(testCache("m2"))
        dao.insert(testCache("m3"))
        val result = dao.getByIds(listOf("m1", "m3"))
        assertEquals(2, result.size)
    }

    @Test
    fun cacheDao_getRandom() = runTest {
        val dao = db.mangaCacheDao()
        dao.insert(testCache("m1"))
        val random = dao.getRandom()
        assertNotNull(random)
    }

    // ─── DownloadTaskDao ──────────────────────────────────────────────────

    @Test
    fun downloadTaskDao_upsertAndGetById() = runTest {
        val dao = db.downloadTaskDao()
        dao.upsert(testDownloadTask("t1", status = "queued"))
        val task = dao.getById("t1")
        assertNotNull(task)
        assertEquals("queued", task?.status)
    }

    @Test
    fun downloadTaskDao_getPendingByChapter_filtersByStatus() = runTest {
        val dao = db.downloadTaskDao()
        dao.upsert(testDownloadTask("t1", chapterUrl = "ch1", mangaId = "m1", status = "queued"))
        dao.upsert(testDownloadTask("t2", chapterUrl = "ch1", mangaId = "m1", status = "completed"))
        val pending = dao.getPendingByChapter("ch1", "m1")
        assertNotNull(pending)
        assertEquals("t1", pending?.id)
    }

    @Test
    fun downloadTaskDao_updateState_modifiesFields() = runTest {
        val dao = db.downloadTaskDao()
        dao.upsert(testDownloadTask("t1"))
        dao.updateState("t1", "running", 0.5f, 5, 10, System.currentTimeMillis(), null)
        val task = dao.getById("t1")
        assertEquals("running", task?.status)
        assertEquals(0.5f, task?.progress)
    }

    @Test
    fun downloadTaskDao_clearCompleted_removesOnlyCompleted() = runTest {
        val dao = db.downloadTaskDao()
        dao.upsert(testDownloadTask("t1", status = "completed"))
        dao.upsert(testDownloadTask("t2", status = "running"))
        dao.clearCompleted()
        assertNull(dao.getById("t1"))
        assertNotNull(dao.getById("t2"))
    }

    @Test
    fun downloadTaskDao_deleteByMangaId() = runTest {
        val dao = db.downloadTaskDao()
        dao.upsert(testDownloadTask("t1", mangaId = "m1"))
        dao.upsert(testDownloadTask("t2", mangaId = "m2"))
        dao.deleteByMangaId("m1")
        assertNull(dao.getById("t1"))
        assertNotNull(dao.getById("t2"))
    }

    // ─── DownloadedMangaDao ───────────────────────────────────────────────

    @Test
    fun downloadedMangaDao_upsertAndGet() = runTest {
        val dao = db.downloadedMangaDao()
        dao.upsert(testDownloadedManga("m1"))
        val manga = dao.get("m1")
        assertNotNull(manga)
        assertEquals("Manga 1", manga?.title)
    }

    @Test
    fun downloadedMangaDao_updateChapterCount() = runTest {
        val dao = db.downloadedMangaDao()
        dao.upsert(testDownloadedManga("m1"))
        dao.updateChapterCount("m1", count = 5)
        val manga = dao.get("m1")
        assertEquals(5, manga?.downloadedChapters)
    }

    @Test
    fun downloadedMangaDao_delete() = runTest {
        val dao = db.downloadedMangaDao()
        dao.upsert(testDownloadedManga("m1"))
        dao.delete("m1")
        assertNull(dao.get("m1"))
    }

    // ─── ReaderAnnotationDao ──────────────────────────────────────────────

    @Test
    fun annotationDao_upsertAndGet() = runTest {
        val dao = db.readerAnnotationDao()
        dao.upsert(ReaderAnnotationEntity("m1", "ch1", 0, note = "Test note"))
        val ann = dao.get("m1", "ch1", 0)
        assertNotNull(ann)
        assertEquals("Test note", ann?.note)
    }

    @Test
    fun annotationDao_delete() = runTest {
        val dao = db.readerAnnotationDao()
        dao.upsert(ReaderAnnotationEntity("m1", "ch1", 0))
        dao.delete("m1", "ch1", 0)
        assertNull(dao.get("m1", "ch1", 0))
    }

    @Test
    fun annotationDao_observeChapterAnnotations_emitsFlow() = runTest {
        val dao = db.readerAnnotationDao()
        dao.upsert(ReaderAnnotationEntity("m1", "ch1", 0, isBookmarked = true))
        dao.upsert(ReaderAnnotationEntity("m1", "ch1", 1, note = "Note"))
        val annotations = dao.observeChapterAnnotations("m1", "ch1").first()
        assertEquals(2, annotations.size)
    }

    @Test
    fun annotationDao_getAll_respectsLimit() = runTest {
        val dao = db.readerAnnotationDao()
        for (i in 1..10) {
            dao.upsert(ReaderAnnotationEntity("m1", "ch1", i))
        }
        val all = dao.getAll(limit = 5)
        assertEquals(5, all.size)
    }

    // ─── Test Helpers ─────────────────────────────────────────────────────

    private fun testFavorite(
        mangaId: String,
        slug: String = "slug-$mangaId",
        title: String = "Manga $mangaId"
    ) = FavoriteEntity(
        mangaId = mangaId,
        slug = slug,
        title = title,
        coverUrl = "https://example.com/cover.jpg",
        sourceId = "azora"
    )

    private fun testHistory(
        mangaId: String,
        lastReadAt: Long = System.currentTimeMillis()
    ) = ReadingHistoryEntity(
        mangaId = mangaId,
        slug = "slug-$mangaId",
        title = "Manga $mangaId",
        coverUrl = "https://example.com/cover.jpg",
        sourceId = "azora",
        lastChapterNumber = 1.0f,
        lastReadAt = lastReadAt
    )

    private fun testCache(
        mangaId: String,
        cachedAt: Long = System.currentTimeMillis()
    ) = MangaCacheEntity(
        mangaId = mangaId,
        slug = "slug-$mangaId",
        title = "Manga $mangaId",
        coverUrl = "https://example.com/cover.jpg",
        sourceId = "azora",
        cachedAt = cachedAt
    )

    private fun testDownloadTask(
        id: String,
        chapterUrl: String = "https://example.com/chapter",
        mangaId: String = "m1",
        status: String = "queued"
    ) = DownloadTaskEntity(
        id = id,
        mangaId = mangaId,
        chapterUrl = chapterUrl,
        targetDir = "/tmp/downloads/$id",
        status = status
    )

    private fun testDownloadedManga(
        mangaId: String
    ) = DownloadedMangaEntity(
        mangaId = mangaId,
        slug = "slug-$mangaId",
        title = "Manga $mangaId",
        coverUrl = "https://example.com/cover.jpg",
        sourceId = "azora"
    )
}
