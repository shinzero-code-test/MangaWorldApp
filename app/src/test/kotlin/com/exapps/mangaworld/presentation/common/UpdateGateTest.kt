package com.exapps.mangaworld.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the in-app update gate (v9.8.0).
 *
 * The gate decides whether a fleet build is blocked (below the published
 * minimum) or merely nagged (below the newest release). Two properties are
 * load-bearing:
 *
 *  1. **Numeric** segment compare — `"9.7.10"` is NEWER than `"9.7.2"`. A
 *     lexicographic compare gets that backwards and would block a fleet on
 *     the newest build.
 *  2. **Fail-open** — an unusable threshold never blocks. A typo in Remote
 *     Config must degrade to "no prompt", never to "the app does not start"
 *     for every device at once.
 */
class UpdateGateTest {

    // ── compareVersions ────────────────────────────────────────────────────

    @Test
    fun equalVersionsCompareEqual() {
        assertEquals(0, UpdateGate.compareVersions("9.7.2", "9.7.2")!!)
        assertEquals(0, UpdateGate.compareVersions("9.7", "9.7")!!)
        assertEquals(0, UpdateGate.compareVersions("10.0.0", "10.0.0")!!)
    }

    @Test
    fun newerVersionComparesGreater() {
        assertTrue(UpdateGate.compareVersions("9.8.0", "9.7.2")!! > 0)
        assertTrue(UpdateGate.compareVersions("10.0.0", "9.99.99")!! > 0)
        assertTrue(UpdateGate.compareVersions("9.7.3", "9.7.2")!! > 0)
    }

    @Test
    fun olderVersionComparesSmaller() {
        assertTrue(UpdateGate.compareVersions("9.7.2", "9.8.0")!! < 0)
        assertTrue(UpdateGate.compareVersions("9.6.0", "9.7.0")!! < 0)
        assertTrue(UpdateGate.compareVersions("8.8.0", "9.0.0")!! < 0)
    }

    /**
     * The bug this helper exists to prevent: `"9.7.10" > "9.7.2"` NUMERICALLY,
     * but `"9.7.10" < "9.7.2"` lexicographically.
     */
    @Test
    fun segmentComparisonIsNumericNotLexicographic() {
        assertTrue(UpdateGate.compareVersions("9.7.10", "9.7.2")!! > 0)
        assertTrue(UpdateGate.compareVersions("9.7.2", "9.7.10")!! < 0)
        // The lexicographic answer would be the opposite of the right one.
        assertTrue("9.7.10" < "9.7.2")
        assertTrue(UpdateGate.compareVersions("9.10.0", "9.9.0")!! > 0)
    }

    @Test
    fun differentSegmentCountsAreZeroPadded() {
        // Missing segments read as 0.
        assertEquals(0, UpdateGate.compareVersions("9.7", "9.7.0")!!)
        assertEquals(0, UpdateGate.compareVersions("9.7.0", "9.7")!!)
        assertEquals(0, UpdateGate.compareVersions("9", "9.0.0.0")!!)
        assertTrue(UpdateGate.compareVersions("9.7.1", "9.7")!! > 0)
        assertTrue(UpdateGate.compareVersions("9.7", "9.7.1")!! < 0)
    }

    @Test
    fun invalidVersionsCompareToNull() {
        assertNull(UpdateGate.compareVersions("", "9.7.2"))
        assertNull(UpdateGate.compareVersions("9.7.2", ""))
        assertNull(UpdateGate.compareVersions("abc", "9.7.2"))
        assertNull(UpdateGate.compareVersions("9.7.2", "not-a-version"))
        assertNull(UpdateGate.compareVersions("9.x.2", "9.7.2"))
        assertNull(UpdateGate.compareVersions("9..2", "9.7.2"))
        assertNull(UpdateGate.compareVersions("-1.0.0", "9.7.2"))
    }

    // ── parseVersion ───────────────────────────────────────────────────────

    @Test
    fun parsesPlainVersions() {
        assertEquals(listOf(9, 7, 2), UpdateGate.parseVersion("9.7.2"))
        assertEquals(listOf(9, 7), UpdateGate.parseVersion("9.7"))
        assertEquals(listOf(10), UpdateGate.parseVersion("10"))
    }

    @Test
    fun ignoresPrefixAndSuffixNoise() {
        // Release tags are published as "v9.7.2"; pre-release builds carry
        // "-rc1" / "+build.7". Neither participates in ordering.
        assertEquals(listOf(9, 7, 2), UpdateGate.parseVersion("v9.7.2"))
        assertEquals(listOf(9, 7, 2), UpdateGate.parseVersion("9.7.2-rc1"))
        assertEquals(listOf(9, 7, 2), UpdateGate.parseVersion("9.7.2+build.7"))
        assertEquals(listOf(9, 7, 2), UpdateGate.parseVersion("  9.7.2  "))
    }

    @Test
    fun rejectsMalformedVersions() {
        assertNull(UpdateGate.parseVersion(""))
        assertNull(UpdateGate.parseVersion("   "))
        assertNull(UpdateGate.parseVersion("abc"))
        assertNull(UpdateGate.parseVersion("9.x.2"))
        assertNull(UpdateGate.parseVersion("9.7."))
        assertNull(UpdateGate.parseVersion("9..2"))
        assertNull(UpdateGate.parseVersion("-1.0.0"))
    }

    // ── evaluate ───────────────────────────────────────────────────────────

    @Test
    fun belowMinimumIsRequired() {
        assertEquals(
            UpdateGate.Decision.Required,
            UpdateGate.evaluate(
                installedVersion = "9.6.0",
                minSupportedVersion = "9.7.2",
                latestVersion = "9.7.2"
            )
        )
    }

    @Test
    fun equalToMinimumIsCurrent() {
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate(
                installedVersion = "9.7.2",
                minSupportedVersion = "9.7.2",
                latestVersion = "9.7.2"
            )
        )
    }

    @Test
    fun aboveMinimumButBelowLatestIsAvailable() {
        assertEquals(
            UpdateGate.Decision.Available,
            UpdateGate.evaluate(
                installedVersion = "9.7.2",
                minSupportedVersion = "9.7.0",
                latestVersion = "9.8.0"
            )
        )
    }

    @Test
    fun atOrAboveLatestIsCurrent() {
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate(
                installedVersion = "9.8.0",
                minSupportedVersion = "9.7.0",
                latestVersion = "9.8.0"
            )
        )
    }

    /**
     * Fail-open: a missing / blank / malformed threshold never blocks. The
     * Remote Config default for `min_supported_version` is blank, and a
     * typo in the console must not brick the fleet.
     */
    @Test
    fun blankOrInvalidMinimumNeverBlocks() {
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.6.0", "", "9.6.0")
        )
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.6.0", "not-a-version", "9.6.0")
        )
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.6.0", "9.x.2", "9.6.0")
        )
    }

    @Test
    fun invalidInstalledVersionNeverBlocks() {
        // "Unknown" must not be read as "old".
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("", "9.7.2", "9.7.2")
        )
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("garbage", "9.7.2", "9.7.2")
        )
    }

    @Test
    fun blankLatestMeansNoNag() {
        // min is satisfied but there is nothing newer to offer.
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.7.2", "9.7.0", "")
        )
    }

    @Test
    fun invalidLatestMeansNoNag() {
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.7.2", "9.7.0", "not-a-version")
        )
    }

    /**
     * The shipped-build case that motivated the whole feature: the fleet on
     * 9.6.x cannot load images and must be blocked, while the fixed 9.7.2
     * build is merely told that 9.8.0 exists.
     */
    @Test
    fun brokenFleetIsBlockedAndFixedBuildIsOnlyNagged() {
        assertEquals(
            UpdateGate.Decision.Required,
            UpdateGate.evaluate("9.6.1", "9.7.2", "9.8.0")
        )
        assertEquals(
            UpdateGate.Decision.Available,
            UpdateGate.evaluate("9.7.2", "9.7.2", "9.8.0")
        )
        assertEquals(
            UpdateGate.Decision.Current,
            UpdateGate.evaluate("9.8.0", "9.7.2", "9.8.0")
        )
    }

    // ── isUsableUpdateUrl ──────────────────────────────────────────────────

    @Test
    fun onlyHttpsUpdateUrlsAreUsable() {
        assertTrue(UpdateGate.isUsableUpdateUrl("https://github.com/o/r/releases/latest"))
        // Remote Config is remote input: no javascript:/intent:/data: schemes.
        assertFalse(UpdateGate.isUsableUpdateUrl("http://github.com/o/r/releases"))
        assertFalse(UpdateGate.isUsableUpdateUrl("javascript:alert(1)"))
        assertFalse(UpdateGate.isUsableUpdateUrl("intent://scan/#Intent;scheme=x;end"))
        assertFalse(UpdateGate.isUsableUpdateUrl(""))
        assertFalse(UpdateGate.isUsableUpdateUrl("   "))
    }
}
