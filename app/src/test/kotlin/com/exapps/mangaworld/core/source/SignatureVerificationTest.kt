package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason
import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: end-to-end manifest pipeline (strict parse → canonicalize → verify).
 *
 * JDK signs, Tink verifies — proving cross-implementation byte agreement, the same
 * property the dashboard signer and app verifier need in production.
 */
class SignatureVerificationTest {

    private fun parserFor(vararg keys: Pair<String, ByteArray>): ManifestParser =
        ManifestParser(trustedKeys = keys.toMap(), host = PluginTestFixtures.HOST)

    @Test
    fun validManifestPasses() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val bytes = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "k1", kp.private)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Valid)
        result as ManifestResult.Valid
        assertEquals("starz", result.manifest.id.value)
        assertEquals(4, result.manifest.version)
        assertEquals("k1", result.keyId)
        assertEquals(
            setOf("starzmanga.com", "cdn.starzmanga.com"),
            result.manifest.effectiveHosts
        )
    }

    @Test
    fun reorderedKeysStillVerify() {
        // Canonicalization proof: byte order on the wire is irrelevant to the signature.
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val signed = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "k1", kp.private)
        val tree = PluginTestFixtures.mapper.readTree(signed) as com.fasterxml.jackson.databind.node.ObjectNode
        val reordered = PluginTestFixtures.reversedJson(tree).toByteArray(Charsets.UTF_8)
        assertTrue(parser.parseAndVerify(reordered) is ManifestResult.Valid)
    }

    @Test
    fun tamperedFieldFailsSignature() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val tree = PluginTestFixtures.manifestTree { it.put("baseUrl", "https://evil.example") }
        // Signed with the ORIGINAL baseUrl, shipped with the tampered one.
        val honest = PluginTestFixtures.manifestTree()
        val honestSig = PluginTestFixtures.sign(kp.private, PluginTestFixtures.canonicalBytesOf(honest))
        tree.put(
            "signature",
            "ed25519:k1:${java.util.Base64.getEncoder().encodeToString(honestSig)}"
        )
        val bytes = PluginTestFixtures.mapper.writeValueAsString(tree).toByteArray(Charsets.UTF_8)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.BAD_SIGNATURE, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun wrongKeyFails() {
        val signer = PluginTestFixtures.generateKeyPair()
        val other = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(other.public))
        val bytes = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "k1", signer.private)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.BAD_SIGNATURE, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun unknownKeyIdRejected() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k9" to PluginTestFixtures.rawPublicKey(kp.public))
        val bytes = PluginTestFixtures.signManifest(PluginTestFixtures.manifestTree(), "k1", kp.private)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.UNKNOWN_KEY_ID, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun missingSignatureRejected() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val bytes = PluginTestFixtures.mapper.writeValueAsString(PluginTestFixtures.manifestTree())
            .toByteArray(Charsets.UTF_8)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.MISSING_SIGNATURE, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun malformedSignaturesRejected() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        listOf(
            "not-a-signature",
            "rsa:k1:AAAA",
            "ed25519:bad key!:AAAA",
            "ed25519:k1:!!!not-base64!!!",
            // Valid base64, wrong length (3 bytes, not 64).
            "ed25519:k1:QUJD"
        ).forEach { header ->
            val tree = PluginTestFixtures.manifestTree { it.put("signature", header) }
            val bytes = PluginTestFixtures.mapper.writeValueAsString(tree).toByteArray(Charsets.UTF_8)
            val result = parser.parseAndVerify(bytes)
            assertTrue("header $header", result is ManifestResult.Invalid)
            assertEquals(
                "header $header",
                ManifestInvalidReason.MALFORMED_SIGNATURE,
                (result as ManifestResult.Invalid).reason
            )
        }
    }

    @Test
    fun duplicateKeysRejectedBeforeVerification() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        // Hand-built: no builder would ever emit this.
        val bytes = """{"id":"starz","id":"evil","signature":"ed25519:k1:QUJD"}"""
            .toByteArray(Charsets.UTF_8)
        val result = parser.parseAndVerify(bytes)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.DUPLICATE_KEYS, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun garbageAndOversizeRejected() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = parserFor("k1" to PluginTestFixtures.rawPublicKey(kp.public))
        val garbage = parser.parseAndVerify("this is not json{".toByteArray(Charsets.UTF_8))
        assertTrue(garbage is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.PARSE_ERROR, (garbage as ManifestResult.Invalid).reason)

        val huge = ByteArray(65 * 1024) { 0x20 }
        val tooLarge = parser.parseAndVerify(huge)
        assertTrue(tooLarge is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.TOO_LARGE, (tooLarge as ManifestResult.Invalid).reason)
    }
}
