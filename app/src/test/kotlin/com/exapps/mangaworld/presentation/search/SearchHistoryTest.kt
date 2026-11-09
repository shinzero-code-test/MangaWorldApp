package com.exapps.mangaworld.presentation.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U-36: search history JSON round-trip. The old `List.toString()` +
 * `split(",")` format split queries containing commas/quotes into phantom
 * entries on reload; JSON persistence must round-trip them verbatim while
 * still migrating legacy toString payloads written by older versions.
 */
class SearchHistoryTest {

    @Test
    fun `empty history round-trips`() {
        assertEquals(emptyList<String>(), parseSearchHistory(serializeSearchHistory(emptyList())))
    }

    @Test
    fun `queries with commas and quotes round-trip`() {
        val history = listOf("one, piece", "said \"hi\"", "plain query", "with ] bracket")
        assertEquals(history, parseSearchHistory(serializeSearchHistory(history)))
    }

    @Test
    fun `unicode queries round-trip`() {
        val history = listOf("ون بيس", "هجوم العمالقة")
        assertEquals(history, parseSearchHistory(serializeSearchHistory(history)))
    }

    @Test
    fun `blank entries are dropped on parse`() {
        assertEquals(listOf("kept"), parseSearchHistory(serializeSearchHistory(listOf("kept", ""))))
    }

    @Test
    fun `legacy toString payload still migrates`() {
        val legacy = "[first query, second query]"
        assertEquals(listOf("first query", "second query"), parseSearchHistory(legacy))
    }

    @Test
    fun `legacy empty payload yields empty history`() {
        assertEquals(emptyList<String>(), parseSearchHistory("[]"))
        assertEquals(emptyList<String>(), parseSearchHistory(""))
    }

    @Test
    fun `garbage input yields empty history instead of throwing`() {
        assertEquals(emptyList<String>(), parseSearchHistory("{not json"))
    }
}
