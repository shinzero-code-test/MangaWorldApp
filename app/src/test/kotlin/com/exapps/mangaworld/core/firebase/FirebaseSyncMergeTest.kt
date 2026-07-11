package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseSyncMergeTest {

    @Test
    fun historyKeepsNewerLocalRecordForDuplicateManga() {
        val local = history(lastReadAt = 200L, title = "Local")
        val remote = history(lastReadAt = 100L, title = "Remote")

        val merged = FirebaseSyncMerge.history(listOf(local), listOf(remote))

        assertEquals(listOf(local), merged)
    }

    @Test
    fun historyKeepsNewerRemoteRecordForDuplicateManga() {
        val local = history(lastReadAt = 100L, title = "Local")
        val remote = history(lastReadAt = 200L, title = "Remote")

        val merged = FirebaseSyncMerge.history(listOf(local), listOf(remote))

        assertEquals(listOf(remote), merged)
    }

    @Test
    fun annotationKeepsLocalRecordWhenTimestampsMatch() {
        val local = ReaderAnnotationEntity("manga", "chapter", 1, "Local", true, 100L)
        val remote = ReaderAnnotationEntity("manga", "chapter", 1, "Remote", false, 100L)

        val merged = FirebaseSyncMerge.annotations(listOf(local), listOf(remote))

        assertEquals(listOf(local), merged)
    }

    private fun history(lastReadAt: Long, title: String) = ReadingHistoryEntity(
        mangaId = "manga",
        slug = "slug",
        title = title,
        coverUrl = "",
        sourceId = "source",
        lastChapterNumber = 1f,
        lastChapterUrl = "chapter",
        lastReadAt = lastReadAt,
        readChapters = 1,
        totalChapters = 1
    )
}
