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
