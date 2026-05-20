package com.exapps.mangaworld.core.data.download

import java.io.File

internal object DownloadStorage {
    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().take(80).ifBlank { "manga" }

    fun chapterKey(chapterUrl: String): String =
        chapterUrl.trimEnd('/').substringAfterLast("/").ifBlank { "chapter" }

    fun canonicalMangaDir(downloadsRoot: File, mangaId: String): File =
        File(downloadsRoot, safeName(mangaId))

    fun legacyMangaDir(downloadsRoot: File, mangaTitle: String?): File? =
        mangaTitle?.takeIf { it.isNotBlank() }?.let { File(downloadsRoot, safeName(it)) }

    fun resolveExistingMangaDir(downloadsRoot: File, mangaId: String, mangaTitle: String? = null): File {
        val canonical = canonicalMangaDir(downloadsRoot, mangaId)
        val legacy = legacyMangaDir(downloadsRoot, mangaTitle)
        return when {
            canonical.exists() -> canonical
            legacy?.exists() == true -> legacy
            else -> canonical
        }
    }

    fun canonicalChapterDir(downloadsRoot: File, mangaId: String, chapterUrl: String): File =
        File(canonicalMangaDir(downloadsRoot, mangaId), chapterKey(chapterUrl))

    fun resolveExistingChapterDir(downloadsRoot: File, mangaId: String, chapterUrl: String, mangaTitle: String? = null): File {
        val canonical = canonicalChapterDir(downloadsRoot, mangaId, chapterUrl)
        if (canonical.exists()) return canonical
        val legacyRoot = legacyMangaDir(downloadsRoot, mangaTitle)
        return if (legacyRoot != null) File(legacyRoot, chapterKey(chapterUrl)) else canonical
    }

    fun migrateLegacyDirectoryIfNeeded(downloadsRoot: File, mangaId: String, mangaTitle: String?) {
        val canonical = canonicalMangaDir(downloadsRoot, mangaId)
        val legacy = legacyMangaDir(downloadsRoot, mangaTitle)
        if (!canonical.exists() && legacy != null && legacy.exists()) {
            legacy.renameTo(canonical)
        }
    }
}
