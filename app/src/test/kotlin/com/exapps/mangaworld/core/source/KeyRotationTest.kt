package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason
import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: key rotation. Two key ids may be simultaneously valid during a rotation
 * window; removing an id revokes it — even for a well-formed signature made by that key.
 */
class KeyRotationTest {

    @Test
    fun bothKeysVerifyDuringRotationWindow() {
        val old = PluginTestFixtures.generateKeyPair()
        val new = PluginTestFixtures.generateKeyPair()
        val parser = ManifestParser(
            trustedKeys = mapOf(
                "2026a" to PluginTestFixtures.rawPublicKey(old.public),
                "2026b" to PluginTestFixtures.rawPublicKey(new.public)
            ),
            host = PluginTestFixtures.HOST
        )
        val signedOld = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "2026a", old.private)
        val signedNew = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "2026b", new.private)
        val r1 = parser.parseAndVerify(signedOld) as ManifestResult.Valid
        val r2 = parser.parseAndVerify(signedNew) as ManifestResult.Valid
        assertEquals("2026a", r1.keyId)
        assertEquals("2026b", r2.keyId)
    }

    @Test
    fun revokedKeyRejectedDespiteValidSignature() {
        val old = PluginTestFixtures.generateKeyPair()
        val parser = ManifestParser(
            // Old key already dropped from the trust map = revoked.
            trustedKeys = mapOf("2026b" to PluginTestFixtures.rawPublicKey(old.public)),
            host = PluginTestFixtures.HOST
        )
        val bytes = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "2026a", old.private)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.UNKNOWN_KEY_ID, (result as ManifestResult.Invalid).reason)
    }
}
