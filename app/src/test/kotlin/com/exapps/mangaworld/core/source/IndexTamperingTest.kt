package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginIndexEntry
import com.exapps.mangaworld.core.source.plugins.PluginStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: `index.json` is untrusted discovery metadata. A forged index — version
 * bump, id swap, URL swap — can never authorise an installation on its own; only a
 * verifying manifest with matching id+version opens the gate.
 */
class IndexTamperingTest {

    private fun validManifest(): ManifestResult.Valid {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = ManifestParser(
            trustedKeys = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public)),
            host = PluginTestFixtures.HOST
        )
        val bytes = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "k1", kp.private)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Valid)
        return result as ManifestResult.Valid
    }

    @Test
    fun matchingHintAndManifestAuthorises() {
        val index = PluginIndexEntry("starz", 4, "https://cdn.example/starz/plugin.json", "8.8.0")
        assertTrue(PluginStorage.authorizeInstall(index, validManifest()))
    }

    @Test
    fun forgedVersionBumpRefused() {
        // Index claims v5; the only verifying manifest is v4. No silent downgrade-stay,
        // no install — the sync layer must fetch v5 or stand still.
        val index = PluginIndexEntry("starz", 5, "https://cdn.example/starz/plugin.json", "8.8.0")
        assertFalse(PluginStorage.authorizeInstall(index, validManifest()))
    }

    @Test
    fun idMismatchRefused() {
        val index = PluginIndexEntry("evil", 4, "https://cdn.example/starz/plugin.json", "8.8.0")
        assertFalse(PluginStorage.authorizeInstall(index, validManifest()))
    }

    @Test
    fun manifestUrlIsNeverConsulted() {
        // URL swap in the index changes nothing: authorization binds id+version of the
        // verified manifest only. (Distribution-URL allow-listing is a Phase 2 fetch-layer rule.)
        val index = PluginIndexEntry("starz", 4, "https://evil.example/pwn.json", "8.8.0")
        assertTrue(PluginStorage.authorizeInstall(index, validManifest()))
    }
}
