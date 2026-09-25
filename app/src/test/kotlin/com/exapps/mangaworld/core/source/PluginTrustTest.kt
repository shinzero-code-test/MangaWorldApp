package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * Phase 2A trust gates: pinned root, trust-age freshness, cross-signed rotation,
 * RC merge semantics, and the production compatibility surface.
 */
class PluginTrustTest {

    private fun nowIso(): String = Instant.now().toString()

    @Test
    fun pinnedKeyIsWellFormed() {
        val keys = PluginTrust.pinnedKeys()
        assertEquals(setOf(PluginTrust.OFFICIAL_KEY_ID), keys.keys)
        assertEquals(32, keys.getValue(PluginTrust.OFFICIAL_KEY_ID).size)
    }

    @Test
    fun freshnessGate() {
        val now = System.currentTimeMillis()
        assertTrue(PluginTrust.isFresh(nowIso(), now))
        assertFalse(PluginTrust.isFresh("2020-01-01T00:00:00Z", now))
        assertFalse(PluginTrust.isFresh("not-a-date", now))
        // Future anchors fail closed (clock-skew/supply games).
        assertFalse(PluginTrust.isFresh("2999-01-01T00:00:00Z", now))
    }

    private fun signRotation(
        newKeyId: String,
        newPub: ByteArray,
        validFrom: String,
        signerPriv: java.security.PrivateKey,
        signerId: String
    ): PluginTrust.RotationAnnouncement {
        val canonical = PluginTrust.rotationCanonicalBytes(
            newKeyId, Base64.getEncoder().encodeToString(newPub), validFrom
        )
        val sig = PluginTestFixtures.sign(signerPriv, canonical)
        return PluginTrust.RotationAnnouncement(
            newKeyId = newKeyId,
            newPublicKey = newPub,
            validFrom = validFrom,
            signedByKeyId = signerId,
            signature = sig
        )
    }

    @Test
    fun rotationSignedByTrustedKeyVerifies() {
        val old = PluginTestFixtures.generateKeyPair()
        val new = PluginTestFixtures.generateKeyPair()
        val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(old.public))
        val ann = signRotation(
            "k2", PluginTestFixtures.rawPublicKey(new.public), nowIso(), old.private, "k1"
        )
        assertTrue(PluginTrust.verifyRotation(ann, trust))
    }

    @Test
    fun rotationFailsClosed() {
        val old = PluginTestFixtures.generateKeyPair()
        val new = PluginTestFixtures.generateKeyPair()
        val trust = mapOf("k1" to PluginTestFixtures.rawPublicKey(old.public))
        val good = signRotation(
            "k2", PluginTestFixtures.rawPublicKey(new.public), nowIso(), old.private, "k1"
        )
        // Unknown signer.
        assertFalse(PluginTrust.verifyRotation(good.copy(signedByKeyId = "evil"), trust))
        // Tampered payload.
        assertFalse(
            PluginTrust.verifyRotation(
                good.copy(newPublicKey = ByteArray(32) { 7 }), trust
            )
        )
        // Stale anchor.
        assertFalse(
            PluginTrust.verifyRotation(
                signRotation(
                    "k2", PluginTestFixtures.rawPublicKey(new.public),
                    "2020-01-01T00:00:00Z", old.private, "k1"
                ),
                trust
            )
        )
        // Bad key id shape.
        assertFalse(
            PluginTrust.verifyRotation(
                signRotation(
                    "Evil Key!", PluginTestFixtures.rawPublicKey(new.public),
                    nowIso(), old.private, "k1"
                ),
                trust
            )
        )
    }

    @Test
    fun rcMergeAcceptsChainedRotationRejectsUnsigned() {
        val k1 = PluginTestFixtures.generateKeyPair()
        val k2 = PluginTestFixtures.generateKeyPair()
        val pinned = mapOf("k1" to PluginTestFixtures.rawPublicKey(k1.public))
        val k2pub = Base64.getEncoder().encodeToString(PluginTestFixtures.rawPublicKey(k2.public))
        val now = nowIso()

        fun announcementJson(newId: String, pub: String, from: String, by: String, priv: java.security.PrivateKey): String {
            val canonical = PluginTrust.rotationCanonicalBytes(newId, pub, from)
            val sig = Base64.getEncoder().encodeToString(PluginTestFixtures.sign(priv, canonical))
            return """{"newKeyId":"$newId","newPublicKeyB64":"$pub","validFrom":"$from","signature":"ed25519:$by:$sig"}"""
        }

        // k1 → k2 (valid), then k2 → k3 chained in the same payload; plus an unsigned forgery.
        val k3 = PluginTestFixtures.generateKeyPair()
        val k3pub = Base64.getEncoder().encodeToString(PluginTestFixtures.rawPublicKey(k3.public))
        val rc = """{"add":[
            ${announcementJson("k2", k2pub, now, "k1", k1.private)},
            ${announcementJson("k3", k3pub, now, "k2", k2.private)},
            {"newKeyId":"evil","newPublicKeyB64":"${"A".repeat(44)}","validFrom":"$now","signature":"ed25519:k1:${"B".repeat(88)}"}
        ]}"""
        val merged = PluginTrust.resolveTrustedKeys(pinned, rc)
        assertTrue(merged.containsKey("k2"))
        assertTrue(merged.containsKey("k3"))
        assertFalse(merged.containsKey("evil"))
        // Pinned trust always survives, even with garbage RC.
        assertEquals(pinned, PluginTrust.resolveTrustedKeys(pinned, "not json{{"))
        assertEquals(pinned, PluginTrust.resolveTrustedKeys(pinned, null))
        assertEquals(pinned, PluginTrust.resolveTrustedKeys(pinned, ""))
    }

    @Test
    fun productionCapabilitiesGateScriptAndFutureApis() {
        val host = PluginTrust.productionCapabilities("9.0.0")
        // Phase 3: the script engine holds a production contract (bridge v1) —
        // a well-formed script manifest verifies (compat gate passes).
        val kp = PluginTestFixtures.generateKeyPair()
        val parser2 = ManifestParser(
            trustedKeys = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public)),
            host = host
        )
        val script = PluginTestFixtures.manifestTree {
            it.put("engine", "script")
            it.put("engineApi", 1)
            it.put("bridgeApi", 1)
            it.put("scriptSha256", "a".repeat(64))
            it.remove("config")
        }
        val scriptResult = parser2.parseAndVerify(PluginTestFixtures.signManifest(script, "k1", kp.private))
        assertTrue(scriptResult is ManifestResult.Valid)
        // Unknown bridge levels still fail closed as incompatible.
        val badBridge = PluginTestFixtures.manifestTree {
            it.put("engine", "script")
            it.put("engineApi", 1)
            it.put("bridgeApi", 9)
            it.put("scriptSha256", "a".repeat(64))
            it.remove("config")
        }
        val badBridgeResult = parser2.parseAndVerify(PluginTestFixtures.signManifest(badBridge, "k1", kp.private))
        assertTrue(badBridgeResult is ManifestResult.Invalid)
        assertEquals(
            com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason.INCOMPATIBLE,
            (badBridgeResult as ManifestResult.Invalid).reason
        )
        // Future engineApi fails closed as incompatible.
        val future = PluginTestFixtures.manifestTree { it.put("engineApi", 9) }
        val futureResult = parser2.parseAndVerify(PluginTestFixtures.signManifest(future, "k1", kp.private))
        assertTrue(futureResult is ManifestResult.Invalid)
        assertEquals(
            com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason.INCOMPATIBLE,
            (futureResult as ManifestResult.Invalid).reason
        )
        // Supported engines map covers the theme/API set plus scripts.
        assertEquals(
            setOf(SourceEngine.MADARA, SourceEngine.MANGAREADER, SourceEngine.ASTRO, SourceEngine.API, SourceEngine.SCRIPT),
            host.supportedEngines.keys
        )
        assertEquals(1, host.supportedBridgeApi)
    }
}
