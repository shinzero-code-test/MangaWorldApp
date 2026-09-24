package com.exapps.mangaworld.core.source.plugins

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Ed25519 verification for plugin manifests (schema v1).
 *
 * Wire format is deliberately boring and cross-platform: raw 64-byte signatures over the
 * raw canonical bytes, verified against raw 32-byte public keys. Any Ed25519 implementation
 * (Node `noble-ed25519`, libsodium, JDK 15+) produces and consumes exactly these bytes, so the
 * dashboard signer and the app verifier agree without exchanging keyset containers.
 *
 * Pure JVM-safe (Tink subtle API + `java.util.Base64`, both fine on minSdk 26 and plain CI).
 * Verification here never throws — every failure mode collapses to `false`/null and the
 * caller maps it to a [ManifestInvalidReason].
 */
object PluginCrypto {

    const val SIGNATURE_ALGORITHM = "ed25519"
    const val RAW_SIGNATURE_BYTES = 64
    const val RAW_PUBLIC_KEY_BYTES = 32

    private val KEY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,32}$")

    data class ParsedSignature(val keyId: String, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedSignature) return false
            return keyId == other.keyId && signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int = 31 * keyId.hashCode() + signature.contentHashCode()
    }

    /**
     * Parses `ed25519:<keyId>:<base64>`. Returns null for any malformed header —
     * including a base64 payload that does not decode to exactly 64 bytes.
     */
    fun parseSignatureHeader(header: String): ParsedSignature? {
        val parts = header.split(":")
        if (parts.size != 3) return null
        val (algorithm, keyId, encoded) = parts
        if (algorithm != SIGNATURE_ALGORITHM) return null
        if (!KEY_ID_REGEX.matches(keyId)) return null
        val signature = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?: return null
        if (signature.size != RAW_SIGNATURE_BYTES) return null
        return ParsedSignature(keyId, signature)
    }

    /**
     * Verifies a raw signature over raw data with a raw public key. Returns false
     * (never throws) on bad lengths, bad keys, bad signatures, or provider errors.
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != RAW_PUBLIC_KEY_BYTES || signature.size != RAW_SIGNATURE_BYTES) {
            return false
        }
        return try {
            Ed25519Verify(publicKey).verify(signature, data)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
