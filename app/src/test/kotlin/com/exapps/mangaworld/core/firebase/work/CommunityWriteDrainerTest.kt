package com.exapps.mangaworld.core.firebase.work

import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * U-18e: the durable community-write path.
 *
 * The write policy lives in [CommunityWriteDrainer] (pure Kotlin over two
 * interfaces) so it can be executed on the JVM: the worker itself is an
 * Android class and is only a thin shell around the drainer.
 *
 * What is pinned here:
 * - a delivered write leaves the outbox;
 * - a TRANSIENT failure is retried (the row stays, the attempt counter grows)
 *   and the caller is told to retry;
 * - a TERMINAL failure is surfaced (recorded + reported), never silently
 *   swallowed;
 * - the retry budget is bounded — a write that never lands stops retrying;
 * - the outbox codec round-trips and survives a corrupt entry.
 */
class CommunityWriteDrainerTest {

    /** In-memory [CommunityWriteOutbox] with the same semantics as the production one. */
    private class FakeCommunityWriteOutbox : CommunityWriteOutbox {
        private val rows = LinkedHashMap<String, PendingCommunityWrite>()
        val recordedFailures = mutableListOf<CommunityWriteFailure>()

        fun row(id: String): PendingCommunityWrite? = rows[id]

        override suspend fun enqueue(write: PendingCommunityWrite) {
            rows[write.id] = write
        }

        override suspend fun remove(id: String) {
            rows.remove(id)
        }

        override suspend fun pending(): List<PendingCommunityWrite> =
            rows.values.sortedBy { it.createdAt }

        override suspend fun recordRetry(id: String, error: String): Int {
            val current = rows[id] ?: return 0
            val attempts = current.attempts + 1
            rows[id] = current.copy(attempts = attempts, lastError = error)
            return attempts
        }

        override suspend fun recordFailure(id: String, error: String) {
            val write = rows.remove(id) ?: return
            recordedFailures += CommunityWriteFailure(
                id = id,
                kind = write.kind,
                error = error,
                at = System.currentTimeMillis()
            )
        }

        override suspend fun failures(): List<CommunityWriteFailure> = recordedFailures.toList()
    }

    /** Executor that records the writes and can be told to fail. */
    private class FakeCommunityWriteExecutor : CommunityWriteExecutor {
        val executed = mutableListOf<String>()
        var failWith: Throwable? = null

        override suspend fun execute(write: PendingCommunityWrite): String {
            executed += write.id
            failWith?.let { throw it }
            return write.id
        }
    }

    private fun write(
        id: String = "w1",
        kind: CommunityWriteKind = CommunityWriteKind.COMMENT,
        createdAt: Long = 1_000L
    ) = PendingCommunityWrite(
        id = id,
        kind = kind,
        mangaId = "m1",
        chapterUrl = null,
        slug = "slug",
        sourceId = "azora",
        text = "hello @friend",
        spoiler = false,
        authorUid = "uid",
        authorName = "Name",
        authorUsername = "name",
        authorAvatarUrl = null,
        authorBadge = "Beginner",
        createdAt = createdAt
    )

    @Test
    fun `a delivered write leaves the outbox`() = runBlocking {
        val outbox = FakeCommunityWriteOutbox()
        val executor = FakeCommunityWriteExecutor()
        outbox.enqueue(write())

        val result = CommunityWriteDrainer(outbox, executor).drain()

        assertEquals(1, result.delivered)
        assertEquals(0, result.retryable)
        assertEquals(0, result.failed)
        assertFalse(result.shouldRetry)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(listOf("w1"), executor.executed)
    }

    @Test
    fun `a transient failure is retried and the row stays pending`() = runBlocking {
        val outbox = FakeCommunityWriteOutbox()
        val executor = FakeCommunityWriteExecutor()
        outbox.enqueue(write())
        executor.failWith = IOException("network down")

        val result = CommunityWriteDrainer(outbox, executor).drain()

        assertEquals(0, result.delivered)
        assertEquals(1, result.retryable)
        assertEquals(0, result.failed)
        assertTrue("the worker must ask for another attempt", result.shouldRetry)
        // The row is NOT lost and the attempt counter grew.
        val row = outbox.row("w1")
        assertNotNull(row)
        assertEquals(1, row!!.attempts)
        assertNotNull(row.lastError)
        // Nothing was recorded as a terminal failure.
        assertTrue(outbox.recordedFailures.isEmpty())

        // The next run (network back) delivers it and clears the row.
        executor.failWith = null
        val second = CommunityWriteDrainer(outbox, executor).drain()
        assertEquals(1, second.delivered)
        assertFalse(second.shouldRetry)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `a permanent failure is surfaced and the row is dropped`() = runBlocking {
        val outbox = FakeCommunityWriteOutbox()
        val executor = FakeCommunityWriteExecutor()
        outbox.enqueue(write())
        // The rules refused the write: retrying could never succeed.
        executor.failWith = IllegalArgumentException("rejected by rules")

        val result = CommunityWriteDrainer(outbox, executor).drain()

        assertEquals(0, result.delivered)
        assertEquals(0, result.retryable)
        assertEquals(1, result.failed)
        assertFalse("a terminal failure must not ask for a retry", result.shouldRetry)
        // The failure is RECORDED, never silently swallowed.
        assertEquals(1, result.failures.size)
        assertEquals("w1", result.failures.single().id)
        assertEquals(1, outbox.recordedFailures.size)
        assertTrue(outbox.recordedFailures.single().error.contains("rejected by rules"))
        // The row is dropped: it would never succeed.
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `a Firestore permission denial is terminal`() = runBlocking {
        val denial = mockk<FirebaseFirestoreException>()
        every { denial.code } returns FirebaseFirestoreException.Code.PERMISSION_DENIED

        assertEquals(
            CommunityWriteFailureKind.TERMINAL,
            classifyCommunityWriteFailure(denial)
        )
        // An offline/unavailable error is retryable instead.
        val unavailable = mockk<FirebaseFirestoreException>()
        every { unavailable.code } returns FirebaseFirestoreException.Code.UNAVAILABLE
        assertEquals(
            CommunityWriteFailureKind.RETRYABLE,
            classifyCommunityWriteFailure(unavailable)
        )
        // A network error is retryable.
        assertEquals(
            CommunityWriteFailureKind.RETRYABLE,
            classifyCommunityWriteFailure(IOException("timeout"))
        )
    }

    @Test
    fun `the retry budget is bounded`() = runBlocking {
        val outbox = FakeCommunityWriteOutbox()
        val executor = FakeCommunityWriteExecutor()
        outbox.enqueue(write())
        executor.failWith = IOException("network down")
        val drainer = CommunityWriteDrainer(outbox, executor)

        var last = drainer.drain()
        repeat(CommunityWriteDrainer.MAX_ATTEMPTS - 1) {
            last = drainer.drain()
        }

        // The budget is spent: the write is recorded, not retried forever.
        assertEquals(1, last.failed)
        assertFalse(last.shouldRetry)
        assertEquals(1, outbox.recordedFailures.size)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `an empty outbox drains to a no-op`() = runBlocking {
        val outbox = FakeCommunityWriteOutbox()
        val executor = FakeCommunityWriteExecutor()

        val result = CommunityWriteDrainer(outbox, executor).drain()

        assertEquals(0, result.delivered)
        assertEquals(0, result.retryable)
        assertEquals(0, result.failed)
        assertFalse(result.shouldRetry)
        assertTrue(executor.executed.isEmpty())
    }

    // ─── Outbox codec ───────────────────────────────────────────────────────

    @Test
    fun `the outbox codec round-trips a write`() {
        val original = write(kind = CommunityWriteKind.REVIEW).copy(
            chapterUrl = "https://x.com/ch/1",
            authorAvatarUrl = "https://x.com/a.png",
            rating = 4,
            reviewTitle = "Title",
            reviewBody = "Body",
            attempts = 2,
            lastError = "boom"
        )

        val decoded = decodeCommunityWrites(encodeCommunityWrites(listOf(original)))

        assertEquals(1, decoded.size)
        val restored = decoded.single()
        assertEquals(original.id, restored.id)
        assertEquals(original.kind, restored.kind)
        assertEquals(original.mangaId, restored.mangaId)
        assertEquals(original.chapterUrl, restored.chapterUrl)
        assertEquals(original.authorAvatarUrl, restored.authorAvatarUrl)
        assertEquals(original.rating, restored.rating)
        assertEquals(original.reviewTitle, restored.reviewTitle)
        assertEquals(original.reviewBody, restored.reviewBody)
        assertEquals(original.attempts, restored.attempts)
        assertEquals(original.lastError, restored.lastError)
        assertEquals(original.spoiler, restored.spoiler)
    }

    @Test
    fun `a null chapter url survives the codec as null`() {
        val decoded = decodeCommunityWrites(encodeCommunityWrites(listOf(write())))
        assertNull(decoded.single().chapterUrl)
    }

    @Test
    fun `a corrupt outbox entry is skipped, not fatal`() {
        val good = write(id = "good")
        val raw = "[{\"id\":\"broken\",\"kind\":\"NOPE\"}," +
            encodeCommunityWrites(listOf(good)).removePrefix("[").removeSuffix("]") + "]"

        val decoded = decodeCommunityWrites(raw)

        assertEquals(1, decoded.size)
        assertEquals("good", decoded.single().id)
    }

    @Test
    fun `the shared chapter key is stable and 24 hex chars`() {
        val key = communityChapterKey("https://x.com/series/chapter-12")
        assertEquals(24, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, communityChapterKey("https://x.com/series/chapter-12"))
        assertFalse(key == communityChapterKey("https://x.com/series/chapter-13"))
    }

    @Test
    fun `mentions are extracted and capped`() {
        assertEquals(listOf("friend"), communityMentions("hello @friend"))
        assertEquals(10, communityMentions((1..20).joinToString(" ") { "@user$it" }).size)
    }
}
