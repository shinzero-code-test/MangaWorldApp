package com.exapps.mangaworld.community

import com.exapps.mangaworld.presentation.community.extractMentionUsernames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mention parser must mirror the server-side extractor exactly — only tagged
 * names the backend recognises become tappable profile links.
 */
class MentionParserTest {

    @Test
    fun extractsSingleMention() {
        assertEquals(listOf("youssef"), extractMentionUsernames("hello @youssef how are you"))
    }

    @Test
    fun extractsMultipleInOrderDeduped() {
        assertEquals(
            listOf("ali", "sara_99"),
            extractMentionUsernames("@ali and @sara_99 plus @ali again")
        )
    }

    @Test
    fun ignoresTooShortAndInvalid() {
        assertEquals(emptyList<String>(), extractMentionUsernames("hi @ab @! @"))
        assertEquals(emptyList<String>(), extractMentionUsernames("no mentions here"))
    }

    @Test
    fun stopsAtPunctuation() {
        assertEquals(listOf("ali"), extractMentionUsernames("thanks @ali."))
        assertEquals(listOf("ali"), extractMentionUsernames("(@ali)"))
    }

    @Test
    fun casePreservedForDisplay() {
        assertEquals(listOf("Ali_99"), extractMentionUsernames("hey @Ali_99"))
    }
}
