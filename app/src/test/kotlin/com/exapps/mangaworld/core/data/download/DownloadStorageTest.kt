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

    @Test
    fun chapterKeySanitizationTable() {
        // Reserved chars collapse, queries strip, blanks fall back.
        assertEquals("12", DownloadStorage.chapterKey("https://source.example/manga/slug/12/"))
        assertEquals("12", DownloadStorage.chapterKey("https://source.example/manga/slug/12?utm=x"))
        assertEquals("a_b", DownloadStorage.chapterKey("https://source.example/a:b"))
        assertEquals("chapter", DownloadStorage.chapterKey("https://source.example/chapters/.."))
        assertEquals("chapter", DownloadStorage.chapterKey(""))
    }

    @Test
    fun existingMangaDirPrefersCanonicalOverLegacy() {
        val root = temporaryDirectory()
        try {
            val canonical = DownloadStorage.canonicalMangaDir(root, "olympus_solo")
            canonical.mkdirs()
            // Legacy dir exists too (pre-migration leftover) — canonical wins.
            File(root, "Solo Leveling").mkdirs()
            val resolved = DownloadStorage.resolveExistingMangaDir(root, "olympus_solo", "Solo Leveling")
            assertEquals(canonical.canonicalPath, resolved.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingMangaDirFallsBackToLegacy() {
        val root = temporaryDirectory()
        try {
            val legacy = File(root, "Solo Leveling")
            legacy.mkdirs()
            val resolved = DownloadStorage.resolveExistingMangaDir(root, "olympus_solo", "Solo Leveling")
            assertEquals(legacy.canonicalPath, resolved.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migrateLegacyMovesDirectoryOnce() {
        val root = temporaryDirectory()
        try {
            val legacy = File(root, "Solo Leveling")
            legacy.mkdirs()
            File(legacy, "page.jpg").writeText("x")
            DownloadStorage.migrateLegacyDirectoryIfNeeded(root, "olympus_solo", "Solo Leveling")
            val canonical = DownloadStorage.canonicalMangaDir(root, "olympus_solo")
            assertTrue(canonical.exists())
            assertTrue(!legacy.exists())
            // Second run is a no-op, not an error.
            DownloadStorage.migrateLegacyDirectoryIfNeeded(root, "olympus_solo", "Solo Leveling")
            assertTrue(canonical.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun isMangaDirectoryRejectsOutsidePaths() {
        val root = temporaryDirectory()
        try {
            assertTrue(DownloadStorage.isMangaDirectory(root, File(root, "olympus_solo")))
            assertTrue(!DownloadStorage.isMangaDirectory(root, File("/tmp")))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun temporaryDirectory(): File = File(
        System.getProperty("java.io.tmpdir"),
        "mangaworld-download-test-${System.nanoTime()}"
    ).apply { mkdirs() }
}
