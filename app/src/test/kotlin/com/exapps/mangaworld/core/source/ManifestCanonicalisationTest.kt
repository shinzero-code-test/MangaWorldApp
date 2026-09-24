package com.exapps.mangaworld.core.source

import org.erdtman.jcs.JsonCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0 gate: JCS (RFC 8785) canonicalization.
 *
 * Proves the pinned library emits byte-exact canonical form: key ordering (including
 * numeric-like keys, which sort lexicographically, not numerically), nesting, string
 * escapes, number normalization, and non-ASCII passthrough — plus self-consistency
 * (reordered input → identical bytes), which is what makes key-reorder a non-event
 * for signatures.
 */
class ManifestCanonicalisationTest {

    private fun canonical(json: String): String =
        JsonCanonicalizer(json).getEncodedString()

    @Test
    fun keysSortLexicographically() {
        assertEquals("""{"a":2,"b":1}""", canonical("""{"b":1,"a":2}"""))
    }

    @Test
    fun numericLikeKeysSortAsStrings() {
        // "1" < "10" < "111" < "A" < "a" by UTF-16 code units.
        assertEquals(
            """{"":"empty","1":1,"10":2,"111":3,"A":4,"a":5}""",
            canonical("""{"a":5,"A":4,"111":3,"10":2,"1":1,"": "empty"}""")
        )
    }

    @Test
    fun nestingAndArraysPreserved() {
        assertEquals(
            """{"a":{"f":{"F":5,"f":"hi"}},"b":[2,3]}""",
            canonical("""{ "b" : [2, 3], "a" : {"f" : {"f" : "hi", "F" : 5}} }""")
        )
    }

    @Test
    fun floatIntegralNormalizes() {
        assertEquals("""{"n":56}""", canonical("""{"n": 56.0}"""))
    }

    @Test
    fun controlEscapesUseShortForms() {
        assertEquals("""{"k":"a\nb"}""", canonical("""{"k": "a\u000Ab"}"""))
    }

    @Test
    fun nonAsciiPassesThroughUnescaped() {
        assertEquals("""{"ar":"ستارز"}""", canonical("""{"ar":"ستارز"}"""))
    }

    @Test
    fun emptyObjectAndArray() {
        assertEquals("""{}""", canonical("""{ }"""))
        assertEquals("""[]""", canonical("""[ ]"""))
    }

    @Test
    fun readmeCrazyVector() {
        // The library's own documented example — pins the dependency's behavior.
        assertEquals(
            """{"":"empty","1":{"\n":56,"f":{"F":5,"f":"hi"}},"10":{},"111":[{"E":"no","e":"yes"}],"A":{},"a":{}}""",
            canonical("""{"1": {"f": {"f":  "hi","F":  5} ,"\n": 56.0},"10": { },"":  "empty","a": { },"111": [ {"e":  "yes","E":  "no" } ],"A": { }}""")
        )
    }

    @Test
    fun canonicalFormIsStable() {
        val once = canonical("""{"z":1,"a":{"y":[3,2],"b":null},"m":"x"}""")
        assertEquals(once, canonical(once))
    }
}
