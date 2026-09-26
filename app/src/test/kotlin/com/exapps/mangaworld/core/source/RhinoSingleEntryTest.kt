package com.exapps.mangaworld.core.source

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the interpreter lock (T-review): the ONLY Rhino entry
 * path must stay `ScriptContextFactory` (which pins `optimizationLevel = -1`
 * in `onContextCreated`, asserted per-run by `RhinoInterpreterModeTest`).
 * A direct `Context.enter(` anywhere else could create a codegen-mode context
 * and silently void the "no dynamic code loading" guarantee — fail the build
 * here instead of discovering it in review.
 */
class RhinoSingleEntryTest {

    @Test
    fun noDirectContextEnterOutsideSandbox() {
        // Unit-test workdir is the :app module dir (same convention as
        // BundledPilotTest's asset lookup); fall back to a repo-root relative.
        val roots = listOf(
            File("src/main/kotlin"),
            File(System.getProperty("user.dir"), "src/main/kotlin"),
            File(System.getProperty("user.dir"), "app/src/main/kotlin")
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("app sources not found (tried ${roots.map { it.path }})")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.useLines { lines ->
                    lines.any { line ->
                        // Direct entry call (not a comment, not the factory's own
                        // internal delegation via ContextFactory.call).
                        line.contains("Context.enter(") && !line.trimStart().startsWith("//")
                    }
                }
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(
            "direct Context.enter outside ScriptSandbox: $offenders",
            offenders.isEmpty()
        )
    }
}
