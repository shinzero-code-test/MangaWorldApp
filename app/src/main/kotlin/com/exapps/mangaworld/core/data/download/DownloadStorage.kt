package com.exapps.mangaworld.core.data.download

import java.io.File

internal object DownloadStorage {
    private fun safeName(name: String, fallback: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
            .take(80)
            .takeUnless { it == "." || it == ".." }
            .orEmpty()
            .ifBlank { fallback }

    fun chapterKey(chapterUrl: String): String =
        safeName(chapterUrl.trimEnd('/').substringAfterLast("/"), "chapter")

    fun canonicalMangaDir(downloadsRoot: File, mangaId: String): File {
        val root = downloadsRoot.canonicalFile
        return File(root, safeName(mangaId, "manga")).canonicalFile.also {
            require(isDescendant(root, it)) { "Invalid manga directory" }
        }
    }

    fun legacyMangaDir(downloadsRoot: File, mangaTitle: String?): File? =
        mangaTitle?.takeIf { it.isNotBlank() }?.let { title ->
            val root = downloadsRoot.canonicalFile
            File(root, safeName(title, "manga")).canonicalFile.also {
                require(isDescendant(root, it)) { "Invalid legacy manga directory" }
            }
        }

    fun resolveExistingMangaDir(downloadsRoot: File, mangaId: String, mangaTitle: String? = null): File {
        val canonical = canonicalMangaDir(downloadsRoot, mangaId)
        val legacy = legacyMangaDir(downloadsRoot, mangaTitle)
        return when {
            canonical.exists() -> canonical
            legacy?.exists() == true -> legacy
            else -> canonical
        }
    }

    fun canonicalChapterDir(downloadsRoot: File, mangaId: String, chapterUrl: String): File {
        val mangaDir = canonicalMangaDir(downloadsRoot, mangaId)
        return File(mangaDir, chapterKey(chapterUrl)).canonicalFile.also {
            require(isDescendant(mangaDir, it)) { "Invalid chapter directory" }
        }
    }

    fun resolveExistingChapterDir(downloadsRoot: File, mangaId: String, chapterUrl: String, mangaTitle: String? = null): File {
        val canonical = canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
        if (canonical.exists()) return canonical
        val legacyRoot = legacyMangaDir(downloadsRoot, mangaTitle)
        return if (legacyRoot != null) {
            File(legacyRoot, chapterKey(chapterUrl)).canonicalFile.also {
                require(isDescendant(legacyRoot, it)) { "Invalid legacy chapter directory" }
            }
        } else canonical
    }

    fun migrateLegacyDirectoryIfNeeded(downloadsRoot: File, mangaId: String, mangaTitle: String?) {
        val canonical = canonicalMangaDir(downloadsRoot, mangaId)
        val legacy = legacyMangaDir(downloadsRoot, mangaTitle)
        if (!canonical.exists() && legacy != null && legacy.exists()) {
            legacy.renameTo(canonical)
        }
    }

    fun isChapterDirectory(downloadsRoot: File, mangaId: String, directory: File): Boolean =
        runCatching {
            isDescendant(canonicalMangaDir(downloadsRoot, mangaId), directory.canonicalFile)
        }.getOrDefault(false)

    fun isMangaDirectory(downloadsRoot: File, directory: File): Boolean =
        runCatching { isDescendant(downloadsRoot.canonicalFile, directory.canonicalFile) }.getOrDefault(false)

    private fun isDescendant(parent: File, child: File): Boolean =
        child.toPath().startsWith(parent.toPath()) && child != parent
}
