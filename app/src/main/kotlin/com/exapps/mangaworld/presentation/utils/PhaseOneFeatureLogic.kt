package com.exapps.mangaworld.presentation.utils

import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaSource
import java.util.Locale

fun shouldTriggerSmartPrefetch(currentPage: Int, totalPages: Int): Boolean =
    totalPages > 0 && currentPage >= totalPages / 2

fun filterLatestUpdates(
    items: List<LatestChapterItem>,
    selectedSource: MangaSource?,
    unreadOnly: Boolean,
    readStates: Map<String, Boolean>
): List<LatestChapterItem> = items.filter { item ->
    (selectedSource == null || item.source == selectedSource) &&
        (!unreadOnly || readStates[item.chapterUrl] != true)
}

fun normalizeBlacklistInput(text: String): Set<String> =
    text.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()

fun formatDiagnosticBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb)
}
