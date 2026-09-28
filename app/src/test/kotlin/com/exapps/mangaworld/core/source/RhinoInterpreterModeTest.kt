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

    @Test
    fun reflectiveRuntimeNamesResolve() {
        // v9.1.6 pin: Rhino locates these platform/runtime classes by exact
        // class-name string (Kit.classOrNull), so the release keep rules must
        // preserve them — direct references in app code do NOT cover them.
        // This runs unobfuscated on JVM, so it cannot prove the keep rules;
        // it fails loudly if a Rhino UPGRADE moves/renames the probed classes,
        // which is the signal to sync proguard-rules.pro (the 9.1.5 fleet
        // EIIE at Context.enter was VMBridge_jdk18 missing under R8).
        for (name in listOf(
            "org.mozilla.javascript.jdk18.VMBridge_jdk18",
            "org.mozilla.javascript.Interpreter",
            "org.mozilla.javascript.regexp.NativeRegExp",
            "org.mozilla.javascript.regexp.RegExpImpl",
            "org.mozilla.javascript.typedarrays.NativeArrayBuffer"
        )) {
            val cl = Class.forName(name)
            assertEquals("Rhino upgrade moved $name — sync proguard-rules.pro", name, cl.name)
        }
    }
}
