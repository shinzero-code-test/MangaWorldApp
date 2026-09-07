package com.exapps.mangaworld.core.data.download

import java.io.File

internal object DownloadStorage {
    // Control chars and Unicode bidi overrides are filesystem-legal but
    // produce spoofed/confusing display names — strip them at the chokepoint.
    private val UNSAFE_CHARS = Regex("""[\p{C}\u200E\u200F\u202A-\u202E\u2066-\u2069]""")

    private fun safeName(name: String, fallback: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .replace(UNSAFE_CHARS, "")
            .trim()
            .take(80)
            .takeUnless { it == "." || it == ".." }
            .orEmpty()
            .ifBlank { fallback }

    fun chapterKey(chapterUrl: String): String =
        safeName(chapterUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast("/"), "chapter")

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
            if (!legacy.renameTo(canonical)) {
                android.util.Log.w("DownloadStorage", "Legacy dir migration failed: $legacy -> $canonical")
            }
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

/**
 * Sniffs JPEG/PNG/GIF/BMP/WEBP/HEIF magic bytes. An HTML error page saved
 * as .jpg must never count as a downloaded page or a permanent cover.
 */
internal fun File.hasImageMagic(): Boolean = runCatching {
    if (!isFile || length() < 16L) return false
    inputStream().use { inp ->
        val header = ByteArray(12)
        var read = 0
        while (read < header.size) {
            val n = inp.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        if (read < 4) return false
        val b = header
        fun asciiAt(i: Int, s: String): Boolean =
            read >= i + s.length && s.indices.all { k -> b[i + k] == s[k].code.toByte() }
        // JPEG FF D8 | PNG 89 50 | GIF 47 49 | BMP 42 4D
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 0x89.toByte() && asciiAt(1, "PNG")) ||
            asciiAt(0, "GIF8") || asciiAt(0, "BM") ||
            // WEBP RIFF....WEBP | HEIF ftyp
            (asciiAt(0, "RIFF") && asciiAt(8, "WEBP")) || asciiAt(4, "ftyp")
    }
}.getOrDefault(false)
