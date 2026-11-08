package com.exapps.mangaworld.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * U-13: [suspendRunCatching] must rethrow [CancellationException] while
 * capturing all other failures exactly like [runCatching].
 */
class SuspendRunCatchingTest {

    @Test
    fun success_returnsValue() = runBlocking {
        val r = suspendRunCatching { 41 + 1 }
        assertTrue(r.isSuccess)
        assertEquals(42, r.getOrThrow())
    }

    @Test
    fun failure_returnsException() = runBlocking {
        val r = suspendRunCatching { throw IllegalStateException("x") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellation_isRethrown() = runBlocking {
        try {
            suspendRunCatching { throw CancellationException("stop") }
            fail("CancellationException must propagate, not become Result.failure")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
