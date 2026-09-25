package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginIndexParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2B index gates: the polled artifact is untrusted discovery metadata —
 * malformed, oversized, or schemaless payloads fail closed before any manifest
 * is ever downloaded.
 */
class PluginIndexParserTest {

    private fun index(vararg entries: String, schema: Int = 1): ByteArray =
        """{"schemaVersion":$schema,"updatedAt":"2026-09-24T00:00:00Z","entries":[${entries.joinToString(",")}]}"""
            .toByteArray(Charsets.UTF_8)

    private fun entry(
        id: String = "hijala",
        version: Int = 2,
        kind: String = "descriptor",
        minApp: String = "8.9.0",
        url: String = "https://cdn.example/plugins/hijala/v2/plugin.json"
    ) = """{"id":"$id","version":$version,"kind":"$kind","minAppVersion":"$minApp","manifestUrl":"$url"}"""

    @Test
    fun validIndexParses() {
        val result = PluginIndexParser.parse(index(entry(), entry("lavascans", 3)))
        assertTrue(result is PluginIndexParser.IndexResult.Valid)
        result as PluginIndexParser.IndexResult.Valid
        assertEquals(2, result.entries.size)
        assertEquals("hijala", result.entries[0].id)
        assertEquals(2, result.entries[0].version)
        assertEquals("descriptor", result.entries[0].kind)
    }

    @Test
    fun emptyEntriesValid() {
        val result = PluginIndexParser.parse(index())
        assertTrue(result is PluginIndexParser.IndexResult.Valid)
        assertEquals(0, (result as PluginIndexParser.IndexResult.Valid).entries.size)
    }

    @Test
    fun oversizeRejected() {
        val big = ByteArray(PluginIndexParser.MAX_INDEX_BYTES + 1) { 'x'.code.toByte() }
        val result = PluginIndexParser.parse(big)
        assertTrue(result is PluginIndexParser.IndexResult.Invalid)
    }

    @Test
    fun badSchemaVersionRejected() {
        assertTrue(PluginIndexParser.parse(index(entry(), schema = 2)) is PluginIndexParser.IndexResult.Invalid)
        assertTrue(PluginIndexParser.parse("{\"updatedAt\":\"x\",\"entries\":[]}".toByteArray()) is PluginIndexParser.IndexResult.Invalid)
        assertTrue(PluginIndexParser.parse("not json".toByteArray()) is PluginIndexParser.IndexResult.Invalid)
    }

    @Test
    fun badEntriesRejected() {
        // Bad id.
        assertTrue(PluginIndexParser.parse(index(entry(id = "Hijala"))) is PluginIndexParser.IndexResult.Invalid)
        // Bad version.
        assertTrue(
            PluginIndexParser.parse(
                index("""{"id":"hijala","version":0,"kind":"descriptor","minAppVersion":"8.9.0","manifestUrl":"https://x/y"}""")
            ) is PluginIndexParser.IndexResult.Invalid
        )
        // Blank kind.
        assertTrue(PluginIndexParser.parse(index(entry(kind = ""))) is PluginIndexParser.IndexResult.Invalid)
        // Bad minAppVersion.
        assertTrue(PluginIndexParser.parse(index(entry(minApp = "8.9"))) is PluginIndexParser.IndexResult.Invalid)
        // Blank manifestUrl.
        assertTrue(PluginIndexParser.parse(index(entry(url = ""))) is PluginIndexParser.IndexResult.Invalid)
    }

    @Test
    fun duplicateIdsFirstWins() {
        val result = PluginIndexParser.parse(index(entry(version = 2), entry(version = 9)))
        assertTrue(result is PluginIndexParser.IndexResult.Valid)
        result as PluginIndexParser.IndexResult.Valid
        assertEquals(1, result.entries.size)
        assertEquals(2, result.entries[0].version)
    }

    @Test
    fun unknownKindAcceptedAsHint() {
        // `kind` never authorises anything; the signed manifest wins conflicts.
        val result = PluginIndexParser.parse(index(entry(kind = "something-new")))
        assertTrue(result is PluginIndexParser.IndexResult.Valid)
        assertEquals(
            "something-new",
            (result as PluginIndexParser.IndexResult.Valid).entries[0].kind
        )
    }

    @Test
    fun hugeEntryCountFailsClosed() {
        // 1001 minimal entries trip a gate (size cap fires first at this
        // density — either way the doc is refused, never partially applied).
        val many = (0 until 1001).joinToString(",") { entry(id = "s$it") }
        val result = PluginIndexParser.parse(index(many))
        assertTrue(result is PluginIndexParser.IndexResult.Invalid)
    }
}
