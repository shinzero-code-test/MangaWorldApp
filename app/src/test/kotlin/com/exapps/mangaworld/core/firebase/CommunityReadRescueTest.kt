package com.exapps.mangaworld.core.firebase

import app.cash.turbine.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.backgroundScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the listener watchdog: healthy streams pay
 * nothing, stalled streams heal via one-shot fetch, failures stay loud
 * where they must (upstream) and quiet where they should (rescue).
 */
class CommunityReadRescueTest {

    @Test
    fun fastListener_winsAndFallbackNeverRuns() = runTest {
        var fallbackRan = false
        flowOf(listOf("a")).withStarvationFallback(
            timeoutMs = 5_000L,
            fallback = { fallbackRan = true; listOf("b") }
        ).test {
            assertEquals(listOf("a"), awaitItem())
            awaitComplete()
        }
        assertTrue("fallback must not run when the listener delivers", !fallbackRan)
    }

    @Test
    fun stalledListener_healsViaFallback() = runTest {
        flow<List<String>> { awaitCancellation() }.withStarvationFallback(
            timeoutMs = 5_000L,
            fallback = { listOf("rescued") }
        ).test {
            assertEquals(listOf("rescued"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun lateListener_stillDeliveredAfterRescue() = runTest {
        // Rescue emits first; a recovering listener emission afterwards is
        // harmless (idempotent UI state) — assert both arrive in order.
        var release = false
        flow {
            while (!release) kotlinx.coroutines.delay(1_000L)
            emit("late")
        }.withStarvationFallback(
            timeoutMs = 5_000L,
            fallback = { "rescued" }
        ).test {
            assertEquals("rescued", awaitItem())
            release = true
            assertEquals("late", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upstreamError_propagatesToCaller() = runTest {
        val boom = RuntimeException("denied")
        var fallbackRan = false
        // Direct collect (no Turbine): the raw upstream failure must surface.
        val error = try {
            flow<String> { throw boom }.withStarvationFallback(
                timeoutMs = 5_000L,
                fallback = { fallbackRan = true; "x" }
            ).collect()
            null
        } catch (e: Throwable) {
            e
        }
        assertSame("upstream failure must propagate raw", boom, error)
        assertTrue("no rescue when upstream fails fast", !fallbackRan)
    }

    @Test
    fun failingFallback_reportsButNeverCrashes() = runTest {
        var reported: Throwable? = null
        // backgroundScope: the collection must stay alive across the time
        // advance (cancelling Turbine up-front would kill the watchdog
        // before it ever fires).
        backgroundScope.launch {
            flow<String> { awaitCancellation() }.withStarvationFallback(
                timeoutMs = 10L,
                fallback = { throw RuntimeException("rescue boom") },
                onFallbackError = { reported = it }
            ).collect()
        }
        advanceTimeBy(100L)
        assertNotNull("rescue failure must reach onFallbackError", reported)
    }

    @Test
    fun fallbackError_doesNotBlockLateRecovery() = runTest {
        var reported: Throwable? = null
        var release = false
        val job = launch {
            flow {
                while (!release) kotlinx.coroutines.delay(1_000L)
                emit("late")
            }.withStarvationFallback(
                timeoutMs = 10L,
                fallback = { throw RuntimeException("rescue boom") },
                onFallbackError = { reported = it }
            ).test {
                assertEquals("late", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
        advanceTimeBy(50L)
        assertNotNull(reported)
        release = true
        job.join()
    }

    @Test
    fun nullFallbackValue_isDelivered() = runTest {
        // Nullable flows (public profile doc) rescue to null, not to nothing.
        flow<String?> { awaitCancellation() }.withStarvationFallback(
            timeoutMs = 5_000L,
            fallback = { null }
        ).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
