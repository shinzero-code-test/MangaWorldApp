package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.model.CommunityComment

fun containsBannedKeyword(text: String, bannedKeywords: Set<String>): Boolean =
    bannedKeywords.any { keyword -> keyword.isNotBlank() && text.contains(keyword, ignoreCase = true) }

fun suggestCloudRestoreStrategy(
    localFavorites: Int,
    remoteFavorites: Int,
    localLatestHistoryAt: Long,
    remoteLatestHistoryAt: Long,
    localLatestAnnotationAt: Long,
    remoteLatestAnnotationAt: Long
): CloudRestoreStrategy = when {
    remoteLatestHistoryAt > localLatestHistoryAt -> CloudRestoreStrategy.MERGE
    remoteFavorites > localFavorites -> CloudRestoreStrategy.MERGE
    remoteLatestAnnotationAt > localLatestAnnotationAt -> CloudRestoreStrategy.MERGE
    else -> CloudRestoreStrategy.KEEP_LOCAL
}

fun filterMutedComments(comments: List<CommunityComment>, mutedUserIds: Set<String>): List<CommunityComment> =
    comments.filterNot { it.authorUid in mutedUserIds }
