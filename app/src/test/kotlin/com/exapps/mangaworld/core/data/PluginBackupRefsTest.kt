package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
import com.exapps.mangaworld.core.source.plugins.PluginOrigin
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 backup-by-reference (§11A): backups carry `{id, version, origin}`
 * for installed plugins — never payload bytes — so restore re-syncs verified
 * payloads instead of resurrecting stale or revoked ones.
 */
class PluginBackupRefsTest {

    private fun record(
        id: String = "hijala",
        version: Int? = 2,
        origin: PluginOrigin = PluginOrigin.OFFICIAL
    ) = PluginIndexRecord(id, version, 1, origin, PluginStatus.ENABLED, null)

    @Test
    fun roundTripPreservesReference() {
        val json = pluginRefToJson(record())
        assertEquals("hijala", json.getString("id"))
        assertEquals(2, json.getInt("version"))
        assertEquals("OFFICIAL", json.getString("origin"))
        val back = json.toPluginRef()
        assertEquals(PluginRef("hijala", 2, PluginOrigin.OFFICIAL), back)
    }

    @Test
    fun customOriginSurvives() {
        val json = pluginRefToJson(record("lab", 1, PluginOrigin.CUSTOM))
        assertEquals(PluginOrigin.CUSTOM, json.toPluginRef().origin)
    }

    @Test
    fun badIdsRejected() {
        listOf("Hijala", "has space", "", "a".repeat(65), "../evil").forEach { id ->
            try {
                JSONObject().put("id", id).put("version", 1).put("origin", "OFFICIAL").toPluginRef()
                fail("expected rejection for $id")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun badVersionsRejected() {
        listOf(0, -2).forEach { version ->
            try {
                JSONObject().put("id", "hijala").put("version", version).put("origin", "OFFICIAL")
                    .toPluginRef()
                fail("expected rejection for $version")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun unknownOriginRejected() {
        try {
            JSONObject().put("id", "hijala").put("version", 1).put("origin", "ADMIN").toPluginRef()
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun missingFieldsRejected() {
        try {
            JSONObject().put("id", "hijala").toPluginRef()
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }
}
