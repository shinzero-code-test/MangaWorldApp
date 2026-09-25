package com.exapps.mangaworld.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Phase 3 bridge contract: fetch/parse/select/resolve/log behave, and every
 * input bound fails the call (never the process).
 *
 * Assertion discipline: Rhino may surface host exceptions wrapped — every
 * refusal asserts on the joined cause-chain messages, never the top frame.
 */
class ScriptBridgeTest {

    private val html = """
        <html><head><title>T</title></head><body>
        <div class="card"><a href="/manga/one/">One</a><img data-src="https://cdn.script.example/1.jpg"></div>
        <div class="card"><a href="https://script.example/manga/two/">Two</a><img src="/2.jpg"></div>
        </body></html>
    """.trimIndent()

    @Before
    fun clear() = ScriptTestSupport.clearLogs()

    private fun sessionWith(
        bodies: Map<String, ByteArray> = emptyMap(),
        status: Int = 200
    ) = ScriptTestSupport.session(
        fetcher = ScriptTestSupport.FakeFetcher(
            bodies = bodies.toMutableMap(),
            defaultStatus = status
        )
    )

    /** Evals [script]; returns the JSON result or fails when it throws. */
    private fun evalOk(script: String, session: ScriptBridgeSession): String =
        ScriptTestSupport.evalJson(script, session)
            ?: throw AssertionError("expected a value, script returned undefined")

    /** Evals [script]; expects a throw whose chain mentions every [needle]. */
    private fun evalFails(script: String, session: ScriptBridgeSession, vararg needles: String) {
        try {
            ScriptTestSupport.evalJson(script, session)
        } catch (e: Exception) {
            val chain = generateSequence(e as Throwable) { it.cause }
                .mapNotNull { it.message }.joinToString("\n")
            needles.forEach { needle ->
                assertTrue(
                    "expected [$needle] in chain:\n$chain",
                    chain.contains(needle)
                )
            }
            return
        }
        fail("expected refusal, script succeeded")
    }

    @Test
    fun fetchServesAllowlistedBody() {
        val fetcher = ScriptTestSupport.FakeFetcher(
            bodies = mutableMapOf("https://script.example/" to "hello".toByteArray())
        )
        val session = ScriptTestSupport.session(fetcher = fetcher)
        assertEquals("\"hello\"", evalOk("fetch('https://script.example/')", session))
        // Manifest hosts travel with the call (fetcher re-validates per hop).
        assertEquals(setOf("script.example", "cdn.script.example"), fetcher.seen.single().third)
    }

    @Test
    fun fetchRejectsOffAllowlistHost() {
        evalFails(
            "fetch('https://evil.example/')", sessionWith(),
            "host not allowed"
        )
    }

    @Test
    fun fetchRejectsPlainHttp() {
        evalFails("fetch('http://script.example/')", sessionWith(), "https")
    }

    @Test
    fun fetchRejectsUserinfoUrls() {
        evalFails("fetch('https://user@script.example/')", sessionWith(), "userinfo")
    }

    @Test
    fun fetchRejectsCallerCookies() {
        evalFails(
            "fetch('https://script.example/', {'Cookie': 'stolen=1'})",
            sessionWith(),
            "Cookie"
        )
    }

    @Test
    fun fetchSurfacesHttpStatus() {
        evalFails(
            "fetch('https://script.example/nope')", sessionWith(status = 404),
            "404"
        )
    }

    @Test
    fun parseSelectResolveRoundTrip() {
        val session = sessionWith()
        val script = """
            (function(){
              var doc = parse(${jsString(html)});
              return {
                texts: selectText(doc, 'div.card a'),
                hrefs: selectAttr(doc, 'div.card a', 'href'),
                covers: selectAttr(doc, 'div.card img', 'data-src'),
                direct: resolveUrl(doc, '/manga/three/'),
                count: selectHtml(doc, 'div.card').length
              };
            })()
        """.trimIndent()
        val json = evalOk(script, session)
        assertTrue(json, json.contains("\"texts\":[\"One\",\"Two\"]"))
        // Relative hrefs absolutize against the doc base (manifest baseUrl).
        assertTrue(json, json.contains("https://script.example/manga/one/"))
        assertTrue(json, json.contains("https://script.example/manga/two/"))
        // data-src passes through; missing data-src on 2nd img yields "".
        assertTrue(json, json.contains("https://cdn.script.example/1.jpg"))
        assertTrue(json, json.contains("\"direct\":\"https://script.example/manga/three/\""))
        assertTrue(json, json.contains("\"count\":2"))
    }

    @Test
    fun unknownHandleFailsClosed() {
        evalFails("selectText(424242, 'a')", sessionWith(), "unknown document handle")
    }

    @Test
    fun badSelectorFailsCall() {
        evalFails(
            "selectText(parse(${jsString(html)}), '[[[invalid')",
            sessionWith(),
            "bad selector"
        )
    }

    @Test
    fun badAttrNameRejected() {
        evalFails(
            "selectAttr(parse(${jsString(html)}), 'a', 'onclick\\nfoo')",
            sessionWith(),
            "bad attr"
        )
    }

    @Test
    fun foreignParseBaseRejected() {
        evalFails(
            "parse(${jsString(html)}, 'https://evil.example/')",
            sessionWith(),
            "baseUrl host not allowed"
        )
    }

    @Test
    fun logRoutesWithTagAndCap() {
        val session = sessionWith()
        // log() returns undefined (un-JSON-stringifiable) — comma-wrap the probe.
        evalOk("(log('warn', 'hello'), 'done')", session)
        assertEquals(
            Triple("warn", "script:scriptpilot", "hello"),
            ScriptTestSupport.logs.single()
        )
    }

    @Test
    fun logRejectsBadLevel() {
        evalFails("log('nope', 'x')", sessionWith(), "bad log level")
    }

    @Test
    fun oversizedInputsRefused() {
        // 501-char selector trips the bound without allocating megabytes.
        evalFails(
            "selectText(parse(${jsString(html)}), 'a'.repeat(300) + '.x'.repeat(101))",
            sessionWith(),
            "exceeds 500 chars"
        )
    }

    /** Embeds text as a JS string literal (no fixture files for unit bounds). */
    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
