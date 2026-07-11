package com.exapps.mangaworld.core.data.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {

    @Test
    fun chapterDirectoryDoesNotEscapeMangaDirectoryForTraversalSegment() {
        val root = temporaryDirectory()
        try {
            val mangaDir = DownloadStorage.canonicalMangaDir(root, "manga")
            val chapterDir = DownloadStorage.canonicalChapterDir(root, "manga", "https://source.example/chapters/..")

            assertEquals("chapter", chapterDir.name)
            assertTrue(chapterDir.toPath().startsWith(mangaDir.toPath()))
            assertTrue(DownloadStorage.isChapterDirectory(root, "manga", chapterDir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mangaIdTraversalSegmentFallsBackToSafeDirectoryName() {
        val root = temporaryDirectory()
        try {
            val mangaDir = DownloadStorage.canonicalMangaDir(root, "..")

            assertEquals("manga", mangaDir.name)
            assertTrue(mangaDir.toPath().startsWith(root.canonicalFile.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun temporaryDirectory(): File = File(
        System.getProperty("java.io.tmpdir"),
        "mangaworld-download-test-${System.nanoTime()}"
    ).apply { mkdirs() }
}
