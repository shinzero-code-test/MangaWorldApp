package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.domain.model.CommunityReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-19 contract tests (pure logic — no Firestore, no network, no Android).
 *
 * They pin the reply routing the old side-channel broke:
 *  - replies are created via POST /api/community/replies (endpoint payload),
 *  - replies never also trigger push-reply (no double tally), so even a
 *    failing notification call cannot undercount a new-path reply,
 *  - root delete gating stays on the server-maintained replyCount.
 *
 * Server-side atomicity (reply + increment in one transaction, surviving
 * FCM failure) is covered by the dashboard vitest for the replies route.
 */
class ReplyCreateContractTest {

    @Test
    fun commentReply_usesRepliesEndpoint() {
        val target = CommunityReplyTarget(parentId = "root1", replyToUid = "uid-root")
        assertTrue(isReplyCreate(target))
        assertEquals("/api/community/replies", REPLIES_ENDPOINT_PATH)
    }

    @Test
    fun reviewReply_usesRepliesEndpoint() {
        val target = CommunityReplyTarget(reviewId = "rev1", replyToUid = "uid-root")
        assertTrue(isReplyCreate(target))
    }

    @Test
    fun topLevelPost_staysOnDirectPath() {
        assertFalse(isReplyCreate(null))
        // A non-null target carrying neither id is a legacy top-level shape,
        // not a reply — it must not be forced through the endpoint (400).
        assertFalse(isReplyCreate(CommunityReplyTarget()))
    }

    @Test
    fun commentPayload_sendsParentExactlyOnce() {
        val target = CommunityReplyTarget(parentId = "root1", replyToUid = "uid-root")
        val payload = buildReplyRequestPayload(
            mangaId = "m1",
            chapterUrl = "https://example/ch/1",
            slug = "test-slug",
            sourceId = "azora",
            text = "hello",
            spoiler = true,
            replyTarget = target
        )
        assertEquals("m1", payload["mangaId"])
        assertEquals("https://example/ch/1", payload["chapterUrl"])
        assertEquals("test-slug", payload["slug"])
        assertEquals("azora", payload["sourceId"])
        assertEquals("hello", payload["text"])
        assertEquals(true, payload["spoiler"])
        assertEquals("root1", payload["parentId"])
        assertFalse("a comment reply must not carry a review id", payload.containsKey("reviewId"))
        // Author snapshot is server-derived — never sent in the payload.
        assertFalse(payload.containsKey("authorUid"))
    }

    @Test
    fun commentPayload_omitsNullChapterUrl() {
        val payload = buildReplyRequestPayload(
            mangaId = "m1",
            chapterUrl = null,
            slug = "test-slug",
            sourceId = "azora",
            text = "hello",
            spoiler = false,
            replyTarget = CommunityReplyTarget(parentId = "root1")
        )
        assertFalse(payload.containsKey("chapterUrl"))
    }

    @Test
    fun reviewPayload_sendsReviewExactlyOnce() {
        val payload = buildReplyRequestPayload(
            mangaId = "m1",
            chapterUrl = null,
            slug = "test-slug",
            sourceId = "azora",
            text = "agree",
            spoiler = false,
            replyTarget = CommunityReplyTarget(reviewId = "rev1")
        )
        assertEquals("rev1", payload["reviewId"])
        assertFalse(payload.containsKey("parentId"))
    }

    @Test
    fun replies_neverNotifyViaPushReply_evenWithMentions() {
        // Notification-failure-still-counts: the tally and the fan-out live
        // in the same server commit for new-path replies, independent of
        // any client notification call — so there is no client call left
        // to fail.
        val commentReply = CommunityReplyTarget(parentId = "root1")
        val reviewReply = CommunityReplyTarget(reviewId = "rev1")
        assertFalse(shouldNotifyViaPushReply(commentReply, listOf("user")))
        assertFalse(shouldNotifyViaPushReply(reviewReply, listOf("user")))
        assertFalse(shouldNotifyViaPushReply(commentReply, emptyList()))
    }

    @Test
    fun topLevelMentions_stillNotifyViaPushReply() {
        assertTrue(shouldNotifyViaPushReply(null, listOf("user")))
        assertFalse(shouldNotifyViaPushReply(null, emptyList()))
    }

    @Test
    fun deleteGating_reflectsTransactionalCount() {
        assertTrue(shouldSoftDeleteRoot(1))
        assertTrue(shouldSoftDeleteRoot(5))
        assertFalse(shouldSoftDeleteRoot(0))
    }
}
