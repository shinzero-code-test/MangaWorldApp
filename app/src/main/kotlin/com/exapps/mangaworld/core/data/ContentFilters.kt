package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaItem

private fun normalizeFilterText(value: String): String = value.lowercase().trim()

fun MangaItem.isBlockedBy(keywords: Set<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = buildString {
        append(title)
        append(' ')
        append(genres.joinToString(" "))
        append(' ')
        append(url)
    }.lowercase()
    return keywords.any { keyword -> keyword.isNotBlank() && haystack.contains(normalizeFilterText(keyword)) }
}

fun LatestChapterItem.isBlockedBy(keywords: Set<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = "$mangaTitle ${chapterTitle.orEmpty()} $mangaSlug".lowercase()
    return keywords.any { keyword -> keyword.isNotBlank() && haystack.contains(normalizeFilterText(keyword)) }
}
