package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginLifecycle
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: lifecycle state machine. Only ENABLED is selectable; no event may
 * silently enable a plugin; REVOKED is terminal; INCOMPATIBLE re-opens on upgrade.
 */
class LifecycleStateTest {

    @Test
    fun onlyEnabledIsSelectable() {
        PluginStatus.entries.forEach { status ->
            assertEquals(
                "status $status",
                status == PluginStatus.ENABLED,
                PluginLifecycle.canSelectForReading(status)
            )
        }
    }

    @Test
    fun happyPathInstallEnable() {
        var s = PluginStatus.AVAILABLE
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.INSTALL_VERIFIED)
        assertEquals(PluginStatus.INSTALLED, s)
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.ENABLE)
        assertEquals(PluginStatus.ENABLED, s)
        assertTrue(PluginLifecycle.canSelectForReading(s))
    }

    @Test
    fun updateCycleReturnsThroughInstalled() {
        var s = PluginStatus.ENABLED
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.UPDATE_KNOWN)
        assertEquals(PluginStatus.UPDATE_AVAILABLE, s)
        assertFalse(PluginLifecycle.canSelectForReading(s))
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.INSTALL_VERIFIED)
        assertEquals(PluginStatus.INSTALLED, s)
    }

    @Test
    fun quarantineAndRecovery() {
        var s = PluginLifecycle.transition(PluginStatus.ENABLED, PluginLifecycle.Event.QUARANTINE)
        assertEquals(PluginStatus.QUARANTINED, s)
        assertFalse(PluginLifecycle.canSelectForReading(s))
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.REVERIFY_OK)
        assertEquals(PluginStatus.INSTALLED, s)
    }

    @Test
    fun revokedIsTerminal() {
        PluginStatus.entries.forEach { from ->
            val revoked = PluginLifecycle.transition(from, PluginLifecycle.Event.REVOKE)
            assertEquals("from $from", PluginStatus.REVOKED, revoked)
            PluginLifecycle.Event.entries.forEach { event ->
                assertEquals(
                    "revoked + $event",
                    PluginStatus.REVOKED,
                    PluginLifecycle.transition(PluginStatus.REVOKED, event)
                )
            }
        }
        assertFalse(PluginLifecycle.canSelectForReading(PluginStatus.REVOKED))
    }

    @Test
    fun incompatibleReopensOnUpgrade() {
        var s = PluginLifecycle.transition(PluginStatus.AVAILABLE, PluginLifecycle.Event.MARK_INCOMPATIBLE)
        assertEquals(PluginStatus.INCOMPATIBLE, s)
        assertFalse(PluginLifecycle.canSelectForReading(s))
        s = PluginLifecycle.transition(s, PluginLifecycle.Event.APP_UPGRADED)
        assertEquals(PluginStatus.AVAILABLE, s)
    }

    @Test
    fun noEventSilentlyEnables() {
        listOf(
            PluginStatus.AVAILABLE,
            PluginStatus.INSTALLED,
            PluginStatus.DISABLED,
            PluginStatus.UPDATE_AVAILABLE,
            PluginStatus.INCOMPATIBLE,
            PluginStatus.INVALID,
            PluginStatus.QUARANTINED
        ).forEach { from ->
            PluginLifecycle.Event.entries.forEach { event ->
                val next = PluginLifecycle.transition(from, event)
                if (next == PluginStatus.ENABLED) {
                    // The only legal path to ENABLED is an explicit ENABLE from
                    // INSTALLED or DISABLED.
                    assertTrue(
                        "from $from via $event",
                        event == PluginLifecycle.Event.ENABLE &&
                            (from == PluginStatus.INSTALLED || from == PluginStatus.DISABLED)
                    )
                }
            }
        }
    }
}
