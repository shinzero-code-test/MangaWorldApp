package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.HostCapabilities
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import org.erdtman.jcs.JsonCanonicalizer

/**
 * Shared helpers for the Phase 0 plugin-contract tests.
 *
 * Signing here uses the JDK Ed25519 provider (JDK 15+, CI runs 17) while verification
 * goes through the app's Tink-backed [PluginCrypto] path — the tests therefore prove
 * cross-implementation agreement on the exact wire bytes, exactly like the dashboard
 * (Node) signer and the app verifier must agree in production.
 */
object PluginTestFixtures {

    val mapper = ObjectMapper()

    val HOST = HostCapabilities(
        appVersion = "8.8.0",
        supportedEngines = SourceEngine.entries.associateWith { 1..3 },
        supportedBridgeApi = 1
    )

    private const val BASE_JSON = """{
      "id": "starz",
      "version": 4,
      "minAppVersion": "8.8.0",
      "issuedAt": "2026-09-01T00:00:00Z",
      "names": {"ar": "ستارز", "en": "Starz"},
      "logo": "logos/starz.png",
      "engine": "madara",
      "engineApi": 2,
      "baseUrl": "https://starzmanga.com",
      "requiresVerification": true,
      "enabledByDefault": true,
      "config": {"ajaxChapters": "/ajax/chapters/"},
      "allowedHosts": ["starzmanga.com", "cdn.starzmanga.com"],
      "timeoutMs": 15000,
      "maxResponseMb": 10
    }"""

    /** Valid unsigned manifest tree; mutate for negative cases, then [sign] it. */
    fun manifestTree(mutator: (ObjectNode) -> Unit = {}): ObjectNode {
        val node = mapper.readTree(BASE_JSON) as ObjectNode
        mutator(node)
        return node
    }

    /** Canonical bytes the pipeline must sign/verify (JCS over the unsigned tree). */
    fun canonicalBytesOf(node: ObjectNode): ByteArray =
        JsonCanonicalizer(mapper.writeValueAsString(node)).getEncodedString()
            .toByteArray(Charsets.UTF_8)

    /** Serializes [node] with keys in REVERSE insertion order (canonicalization proof). */
    fun reversedJson(node: ObjectNode): String {
        val reversed = mapper.createObjectNode()
        node.fields().asSequence().toList().asReversed().forEach { (k, v) ->
            reversed.replace(k, v)
        }
        return mapper.writeValueAsString(reversed)
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /**
     * Raw 32-byte Ed25519 public key from JDK X.509 encoding: fixed 12-byte RFC 8410
     * SubjectPublicKeyInfo header (`30 2a || 30 05 06 03 2b 65 70 || 03 21 00`) + 32 key
     * bytes. Asserts the header so provider format drift fails loudly.
     */
    fun rawPublicKey(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        val expectedHeader = byteArrayOf(
            0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
            0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
            0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
        )
        require(encoded.size == 44 && encoded.copyOfRange(0, 12).contentEquals(expectedHeader)) {
            "unexpected Ed25519 SPKI layout"
        }
        return encoded.copyOfRange(12, 44)
    }

    /** Raw 64-byte Ed25519 signature (what Node noble-ed25519 also produces). */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(data)
        }.sign()

    /** Attaches `ed25519:<keyId>:<base64>` to an unsigned tree and serializes it. */
    fun signManifest(node: ObjectNode, keyId: String, privateKey: PrivateKey): ByteArray {
        val sig = sign(privateKey, canonicalBytesOf(node))
        node.put("signature", "ed25519:$keyId:${Base64.getEncoder().encodeToString(sig)}")
        return mapper.writeValueAsString(node).toByteArray(Charsets.UTF_8)
    }
}
