package com.exapps.mangaworld.features

import com.exapps.mangaworld.core.firebase.containsBannedKeyword
import com.exapps.mangaworld.core.firebase.filterMutedComments
import com.exapps.mangaworld.core.firebase.suggestCloudRestoreStrategy
import com.exapps.mangaworld.domain.model.CloudRestoreStrategy
import com.exapps.mangaworld.domain.model.CommunityComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseFeatureLogicTest {

    @Test
    fun bannedKeywordDetectionIsCaseInsensitive() {
        assertTrue(containsBannedKeyword("This contains Spoiler text", setOf("spoiler")))
        assertFalse(containsBannedKeyword("clean comment", setOf("spoiler")))
    }

    @Test
    fun cloudRestorePrefersMergeWhenRemoteAnnotationsAreNewer() {
        val strategy = suggestCloudRestoreStrategy(
            localFavorites = 2,
            remoteFavorites = 2,
            localLatestHistoryAt = 100,
            remoteLatestHistoryAt = 100,
            localLatestAnnotationAt = 50,
            remoteLatestAnnotationAt = 200
        )
        assertEquals(CloudRestoreStrategy.MERGE, strategy)
    }

    @Test
    fun cloudRestoreStrategyBranchTable() {
        // KEEP_LOCAL only when nothing remote is newer or larger.
        assertEquals(
            CloudRestoreStrategy.KEEP_LOCAL,
            suggestCloudRestoreStrategy(2, 2, 100, 100, 200, 50)
        )
        // A condition reorder that always returns MERGE (data-loss direction
        // for the keep-local case above) fails here.
        assertEquals(
            CloudRestoreStrategy.MERGE,
            suggestCloudRestoreStrategy(2, 5, 100, 100, 200, 50)
        )
        assertEquals(
            CloudRestoreStrategy.MERGE,
            suggestCloudRestoreStrategy(2, 2, 100, 300, 200, 50)
        )
    }

    @Test
    fun bannedKeywordAdversarialInputs() {
        assertFalse(containsBannedKeyword("anything", emptySet()))
        assertFalse(containsBannedKeyword("anything", setOf("", "   ")))
        assertTrue(containsBannedKeyword("هذا حرق للأحداث", setOf("حرق")))
    }

    @Test
    fun mutedFilterEdgeCases() {
        // Empty mute set keeps everything; muting everyone empties the list.
        val comments = listOf(
            CommunityComment(id = "1", mangaId = "m1", authorUid = "u1", authorName = "A", text = "hello"),
            CommunityComment(id = "2", mangaId = "m1", authorUid = "u2", authorName = "B", text = "world")
        )
        assertEquals(2, filterMutedComments(comments, emptySet()).size)
        assertTrue(filterMutedComments(comments, setOf("u1", "u2")).isEmpty())
    }

    @Test
    fun mutedUsersAreFilteredFromComments() {
        val comments = listOf(
            CommunityComment(id = "1", mangaId = "m1", authorUid = "u1", authorName = "A", text = "hello"),
            CommunityComment(id = "2", mangaId = "m1", authorUid = "u2", authorName = "B", text = "world")
        )
        val filtered = filterMutedComments(comments, setOf("u2"))
        assertEquals(1, filtered.size)
        assertEquals("u1", filtered.first().authorUid)
    }
}
