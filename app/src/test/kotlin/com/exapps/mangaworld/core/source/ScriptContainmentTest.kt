package com.exapps.mangaworld.core.source

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 containment gate (plan §5/§10): the sandbox is proven by ATTACK, not
 * by reading the installer. Escape attempts must fail; quotas must trip fast;
 * legitimate code must keep working.
 *
 * Assertion shapes (Rhino semantics):
 * - `gone(name)`: the name is either unresolvable (throw) or `typeof`-silent
 *   (`"undefined"`) — both prove no Java is reachable through it.
 * - `contained(script)`: must throw (calling `undefined`, ReferenceError, or a
 *   bridge/quota refusal).
 */
class ScriptContainmentTest {

    private fun session() = ScriptTestSupport.session()

    /** Runs [script] expecting a throw; returns the joined chain messages. */
    private fun contained(script: String): String {
        try {
            ScriptTestSupport.evalJson(script, session())
        } catch (e: Exception) {
            return generateSequence(e as Throwable) { it.cause }
                .mapNotNull { it.message }.joinToString("\n")
        }
        fail("escape vector succeeded — sandbox breach")
        error("unreachable")
    }

    /** Asserts [name] resolves to nothing (throw or `"undefined"`). */
    private fun gone(name: String, expr: String = "typeof $name") {
        val out = try {
            ScriptTestSupport.evalJson(expr, session())
        } catch (_: Exception) {
            return // Unresolvable: gone.
        }
        assertTrue("$name unexpectedly resolvable: $out", out == "\"undefined\"")
    }

    /** Runs [script] expecting SUCCESS. */
    private fun allowed(script: String): String =
        ScriptTestSupport.evalJson(script, session())
            ?: fail("legitimate script failed")

    @Test
    fun javaRootsAreGone() {
        listOf("Packages", "java", "org", "JavaAdapter", "JavaImporter", "JavaArray")
            .forEach { gone(it) }
        // And dereferencing them throws instead of yielding host objects.
        contained("Packages.java.lang.System.exit(0)")
        contained("java.lang.System.exit(0)")
    }

    @Test
    fun instanceMemberChainYieldsNothing() {
        // Plain values carry no Java members: member access is undefined and
        // invoking it throws — there is no getClass foothold anywhere.
        gone("string proto", "typeof ''.getClass")
        gone("array proto", "typeof [].getClass")
        gone("object proto", "typeof ({}).getClass")
        contained("''.getClass().forName('java.lang.System')")
        contained("log.getClass()")
    }

    @Test
    fun functionConstructorIsJsOnly() {
        // Compiling JS from strings still works (legitimate), but the compiled
        // code sees the same deletedscope: no Java.
        assertTrue(allowed("({}).constructor.constructor('return 41+1')()") == "42")
        assertTrue(allowed("Function('return typeof java')()") == "\"undefined\"")
        assertTrue(allowed("Function('return typeof Packages')()") == "\"undefined\"")
    }

    @Test
    fun noAmbientCapabilities() {
        // Timers, sockets, files, shell built-ins: none installed, none inherited.
        listOf(
            "setTimeout(function(){}, 1)",
            "setInterval(function(){}, 1)",
            "new java.net.Socket('script.example', 443)",
            "load('https://script.example/x.js')",
            "readUrl('https://script.example/')",
            "quit()"
        ).forEach { contained(it) }
    }

    @Test
    fun legitimateCodeStillWorks() {
        val out = allowed(
            "(function(){ var xs = [1,2,3].map(function(x){ return x*x; }); " +
                "return {sum: xs.reduce(function(a,b){ return a+b; }, 0), " +
                "t: 'a'.repeat(3), j: JSON.parse('{\"a\":1}').a}; })()"
        )
        assertTrue(out, out.contains("\"sum\":14"))
        assertTrue(out, out.contains("\"t\":\"aaa\""))
        assertTrue(out, out.contains("\"j\":1"))
    }

    @Test
    fun busyLoopTripsInstructionBudgetFast() {
        val start = System.currentTimeMillis()
        val chain = contained("while(true){ var x = 1+1; }")
        val elapsed = System.currentTimeMillis() - start
        assertTrue(chain, chain.contains("instruction budget exceeded"))
        // A responsive kill switch, not a wall-clock timeout: ~100 observer
        // ticks at interpreter speed on any CI runner.
        assertTrue("budget took ${elapsed}ms", elapsed < 20_000)
    }

    @Test
    fun deepRecursionFailsAsScriptError() {
        val chain = contained("(function f(){ return f(); })()")
        // Either the interpreter stack bound or the instruction budget trips —
        // both are contained script errors, never a raw StackOverflowError.
        assertTrue(
            chain,
            chain.contains("instruction budget exceeded") ||
                chain.contains("Too deep") || chain.contains("stack")
        )
    }

    @Test
    fun oversizedEntryUrlRefused() {
        val chain = contained("fetch('https://script.example/' + 'a'.repeat(3000))")
        assertTrue(chain, chain.contains("exceeds 2048 chars"))
    }

    @Test
    fun bridgeFunctionsExposeNoJava() {
        listOf("fetch", "parse", "selectText", "log").forEach { fn ->
            gone("$fn member", "typeof $fn.getClass")
            contained("$fn.getClass()")
        }
    }
}
