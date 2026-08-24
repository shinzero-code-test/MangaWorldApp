package com.exapps.mangaworld.core.data.remote.scraper

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure text/url parsing helpers shared by all scrapers.
 *
 * Extracted from per-site copy-pasted logic so CI can unit-test the exact
 * production code paths (fixture tests alone never exercised them — M-review).
 */
object ScraperText {

    private val FIRST_NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val VIEWS = Regex("(\\d[\\d,]*)\\s*(?:مشاهدة|view)")

    private val ARABIC_MONTHS = mapOf(
        "يناير" to "Jan", "فبراير" to "Feb", "مارس" to "Mar", "أبريل" to "Apr",
        "مايو" to "May", "يونيو" to "Jun", "يوليو" to "Jul", "أغسطس" to "Aug",
        "سبتمبر" to "Sep", "أكتوبر" to "Oct", "نوفمبر" to "Nov", "ديسمبر" to "Dec"
    )

    /**
     * Extracts ONLY the first number in a chapter label.
     *
     * The old pattern `text.replace("الفصل","").replace("[^0-9.]","")` glued
     * every number together: "الفصل 12 : 3" → "12.3"/"123". First-match keeps
     * the real chapter number and ignores trailing noise (page counts, dates…).
     */
    fun firstChapterNumber(text: String?): Float? {
        if (text.isNullOrBlank()) return null
        return FIRST_NUMBER.find(text)?.value?.toFloatOrNull()
    }

    /** Convenience overload used by href fallbacks: last path segment as a number. */
    fun lastSegmentNumber(url: String): Float? =
        url.substringBefore("?").trimEnd('/').substringAfterLast('/').toFloatOrNull()

    /**
     * Slug from a manga/chapter href across WordPress themes:
     * /manga/slug/, /comics/slug/, /manhwa/slug/ or bare /slug/.
     * Query-stripped; pagination artifacts ("page", bare numbers) rejected.
     */
    fun slugFromHref(href: String): String? {
        val clean = href.substringBefore("?").trimEnd('/')
        val last = clean.substringAfterLast('/')
        val valid = last.isNotBlank() &&
            !last.startsWith("page") &&
            !last.all(Char::isDigit)   // /page/2-style artifacts end in a number
        return if (valid) last.trim() else null
    }

    /** View-count from Arabic/English label text ("1,234 مشاهدة" / "5.6K views"). */
    fun extractViews(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val digits = VIEWS.find(text)?.groupValues?.get(1)?.replace(",", "") ?: return null
        return digits.toLongOrNull()
    }

    /**
     * Parses Arabic relative/absolute date labels ("12 يناير 2024") into epoch ms.
     * Previously copy-pasted across Madara/MangaReader/RockManga.
     */
    fun parseArabicDate(rawText: String?): Long? {
        val text = rawText?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        var normalized = text
        for ((ar, en) in ARABIC_MONTHS) {
            normalized = normalized.replace(ar, en)
        }
        return runCatching {
            SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).parse(normalized)?.time
        }.getOrNull()
    }
}
