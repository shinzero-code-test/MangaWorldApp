package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.script.ScriptContextFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 interpreter lock (plan §5): Rhino's bytecode-generation mode would
 * define and load JVM classes at runtime — the "dynamic code loading" shape
 * the plan rejects for APK extensions. Every context must enter with
 * `optimizationLevel = -1`, on EVERY creation path, not just at app init.
 *
 * The factory is the only creation path ([ScriptContextFactory] is the sole
 * `ContextFactory` subclass in the app — enforced by review, proven per-run
 * here): each test below executes through it and asserts the level observed.
 */
class RhinoInterpreterModeTest {

    @Test
    fun freshFactoryStartsLocked() {
        val factory = ScriptContextFactory()
        factory.run { cx ->
            assertEquals(-1, cx.optimizationLevel)
        }
        assertEquals(-1, factory.lastOptimizationLevel)
    }

    @Test
    fun everyRunReappliesTheLock() {
        val factory = ScriptContextFactory()
        repeat(5) { i ->
            factory.run { cx ->
                assertEquals("run $i escaped interpreter mode", -1, cx.optimizationLevel)
            }
        }
        assertEquals(-1, factory.lastOptimizationLevel)
    }

    @Test
    fun scriptsCannotRaiseTheLevel() {
        // Even script code that sniffs its environment cannot touch the
        // setting: Context is not exposed, and the factory re-applies -1 on
        // every creation regardless.
        val factory = ScriptContextFactory()
        val out = factory.run { cx ->
            val scope = cx.initStandardObjects()
            // No Context/Java access from script: the level simply stays put.
            cx.evaluateString(scope, "1 + 1", "<t>", 1, null)
            cx.optimizationLevel
        }
        assertEquals(-1, out)
        assertTrue(factory.lastOptimizationLevel == -1)
    }

    @Test
    fun sharedTestSandboxIsLocked() {
        // The support sandbox used by every other script test: prove the
        // shared instance too (a second factory must not drift).
        ScriptTestSupport.sandbox.run { cx ->
            assertEquals(-1, cx.optimizationLevel)
        }
    }
}
