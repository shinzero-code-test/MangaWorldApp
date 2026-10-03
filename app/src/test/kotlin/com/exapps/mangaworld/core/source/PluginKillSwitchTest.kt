package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginKillSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-10: kill-switch parsing matches the manifest parser's discipline — byte
 * cap before parsing, strict duplicate rejection, revocation-list cap.
 * Transport is dashboard-credential-gated RC, so this is robustness (a
 * bloated payload degrades every sync), not remote exploit defense.
 */
class PluginKillSwitchTest {

    @Test
    fun validPolicyParses() {
        val policy = PluginKillSwitch.parse(
            """{"revoked":[{"id":"badsrc","version":2}],"disabledEngines":["madara"],"disableAllCustoms":true}"""
        )
        assertEquals(1, policy.revoked.size)
        assertEquals("badsrc", policy.revoked[0].id)
        assertEquals(2, policy.revoked[0].version)
        assertTrue(policy.disabledEngines.isNotEmpty())
        assertTrue(policy.disableAllCustoms)
    }

    @Test
    fun blankIsEmptyPolicy() {
        assertEquals(PluginKillSwitch.Policy.EMPTY, PluginKillSwitch.parse(null))
        assertEquals(PluginKillSwitch.Policy.EMPTY, PluginKillSwitch.parse("  "))
    }

    @Test
    fun oversizePayloadFailsClosedToEmpty() {
        assertEquals(
            PluginKillSwitch.Policy.EMPTY,
            PluginKillSwitch.parse("x".repeat(PluginKillSwitch.MAX_KILLSWITCH_JSON_BYTES + 1))
        )
    }

    @Test
    fun duplicateKeysFailClosedToEmpty() {
        assertEquals(
            PluginKillSwitch.Policy.EMPTY,
            PluginKillSwitch.parse("""{"revoked":[],"revoked":[]}""")
        )
    }

    @Test
    fun overCapRevocationListFailsClosedToEmpty() {
        val many = (1..(PluginKillSwitch.MAX_REVOCATIONS + 1))
            .joinToString(",") { """{"id":"s$it"}""" }
        assertEquals(
            PluginKillSwitch.Policy.EMPTY,
            PluginKillSwitch.parse("""{"revoked":[$many]}""")
        )
    }

    @Test
    fun malformedEntriesSkippedNotFatal() {
        // Entries that fail the id/version shape are dropped individually;
        // the rest of the policy still applies.
        val policy = PluginKillSwitch.parse(
            """{"revoked":[{"id":"BAD ID"},{"id":"good","version":3}]}"""
        )
        assertEquals(1, policy.revoked.size)
        assertEquals("good", policy.revoked[0].id)
    }
}
