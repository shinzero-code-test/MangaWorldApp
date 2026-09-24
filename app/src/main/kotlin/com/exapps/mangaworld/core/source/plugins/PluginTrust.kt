package com.exapps.mangaworld.core.source.plugins

import java.time.Instant
import java.util.Base64

/**
 * Phase 2A trust root: pinned official keys, RC rotation with cross-signing,
 * trust-age enforcement, and production [HostCapabilities].
 *
 * Rules (plan §3 signing spec + Rev.3):
 * - The APK-pinned `official-1` key is always trusted. It can only be replaced
 *   by rotation, never by an unsigned payload.
 * - Remote Config is transport only: a newly trusted keyId is accepted ONLY with a
 *   rotation announcement cross-signed by a currently-trusted key
 *   ("K2 valid from T"). A compromised dashboard/RC credential alone cannot
 *   introduce a trusted key.
 * - Trust age: downloaded sync data (rotation announcements, remote manifests)
 *   older than [MAX_TRUST_AGE_DAYS] against `issuedAt`/`validFrom` is rejected.
 *   Bundled payloads (APK-shipped pilots/builtins) are trusted via the APK
 *   signature instead — age does not apply to them.
 * - Revocation without bricking needs the compromise runbook (Phase 2B); this
 *   slice supports authenticated *addition* only.
 *
 * Pure JVM-safe (no Android, no Firebase) so rotation/age logic is unit-testable.
 */
object PluginTrust {

    const val OFFICIAL_KEY_ID = "official-1"

    /** Raw 32-byte Ed25519 public key, base64. Generated 2026-09-24; private key lives in dashboard secrets, never in repo. */
    const val OFFICIAL_PUBLIC_KEY_B64 = "o01gRyLfjV9Zxuo8rOxYB/kdMvPt9mLHbv/tKN9k9FM="

    /** Maximum age of downloaded trust data (rotation announcements). Bundled payloads exempt. */
    const val MAX_TRUST_AGE_DAYS = 90L

    /** Remote Config transport key carrying rotation announcements (JSON, see [parseRotationConfig]). */
    const val RC_ROTATION_KEY = "plugin_key_rotation"

    /** Pinned trust set: always present, rotation-independent. */
    fun pinnedKeys(): Map<String, ByteArray> = mapOf(
        OFFICIAL_KEY_ID to Base64.getDecoder().decode(OFFICIAL_PUBLIC_KEY_B64)
    )

    /**
     * Trust-age gate for *downloaded* data anchored at an ISO-8601 instant.
     * Unparseable anchors fail closed. [nowMs] is injectable for tests.
     */
    fun isFresh(anchorIso: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val anchor = runCatching { Instant.parse(anchorIso).toEpochMilli() }.getOrNull()
            ?: return false
        if (anchor > nowMs) return false
        return nowMs - anchor <= MAX_TRUST_AGE_DAYS * 24 * 60 * 60 * 1000L
    }

    /**
     * Cross-signed rotation announcement: "key [newKeyId] valid from [validFrom]".
     * Canonical bytes are JCS of `{"newKeyId","newPublicKeyB64","validFrom"}` (sorted —
     * that literal order); the signature header names the *superseded* (old) key id.
     */
    data class RotationAnnouncement(
        val newKeyId: String,
        val newPublicKey: ByteArray,
        val validFrom: String,
        val signedByKeyId: String,
        val signature: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RotationAnnouncement) return false
            return newKeyId == other.newKeyId && newPublicKey.contentEquals(other.newPublicKey) &&
                validFrom == other.validFrom && signedByKeyId == other.signedByKeyId &&
                signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var r = newKeyId.hashCode()
            r = 31 * r + newPublicKey.contentHashCode()
            r = 31 * r + validFrom.hashCode()
            r = 31 * r + signedByKeyId.hashCode()
            r = 31 * r + signature.contentHashCode()
            return r
        }
    }

    /** Exact canonical bytes a rotation announcement is signed over (JCS, sorted keys). */
    fun rotationCanonicalBytes(newKeyId: String, newPublicKeyB64: String, validFrom: String): ByteArray =
        "{\"newKeyId\":\"${jcsEscape(newKeyId)}\"," +
            "\"newPublicKeyB64\":\"${jcsEscape(newPublicKeyB64)}\"," +
            "\"validFrom\":\"${jcsEscape(validFrom)}\"}"
            .toByteArray(Charsets.UTF_8)

    private fun jcsEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /**
     * Verifies one announcement against the *currently* trusted set. The signer must
     * already be trusted (cross-sign rule); the anchor must be fresh and parseable.
     */
    fun verifyRotation(
        announcement: RotationAnnouncement,
        trustedKeys: Map<String, ByteArray>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val oldKey = trustedKeys[announcement.signedByKeyId] ?: return false
        if (!isFresh(announcement.validFrom, nowMs)) return false
        if (!Regex("^[A-Za-z0-9_-]{1,32}$").matches(announcement.newKeyId)) return false
        if (announcement.newPublicKey.size != PluginCrypto.RAW_PUBLIC_KEY_BYTES) return false
        val canonical = rotationCanonicalBytes(
            announcement.newKeyId,
            Base64.getEncoder().encodeToString(announcement.newPublicKey),
            announcement.validFrom
        )
        return PluginCrypto.verify(oldKey, canonical, announcement.signature)
    }

    /**
     * Merges pinned keys with authenticated RC additions. RC JSON shape:
     * `{"add":[{"newKeyId","newPublicKeyB64","validFrom","signedBy","signature"}]}` where
     * `signature` is `ed25519:<signedBy>:<base64>`. Additions apply in order so a
     * chained rotation (K1→K2→K3) resolves within one payload. Unknown/malformed/freshness
     * failures are skipped, never fatal — pinned trust always survives.
     */
    fun resolveTrustedKeys(
        pinned: Map<String, ByteArray> = pinnedKeys(),
        rcJson: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, ByteArray> {
        if (rcJson.isNullOrBlank()) return pinned
        val merged = pinned.toMutableMap()
        val root = runCatching {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(rcJson)
        }.getOrNull() ?: return merged
        val adds = root.get("add")?.takeIf { it.isArray } ?: return merged
        for (node in adds) {
            val ann = parseAnnouncement(node) ?: continue
            if (verifyRotation(ann, merged, nowMs)) {
                merged[ann.newKeyId] = ann.newPublicKey
            }
        }
        return merged
    }

    private fun parseAnnouncement(node: com.fasterxml.jackson.databind.JsonNode): RotationAnnouncement? {
        val newKeyId = node.get("newKeyId")?.takeIf { it.isTextual }?.asText() ?: return null
        val pubB64 = node.get("newPublicKeyB64")?.takeIf { it.isTextual }?.asText() ?: return null
        val validFrom = node.get("validFrom")?.takeIf { it.isTextual }?.asText() ?: return null
        val sigHeader = node.get("signature")?.takeIf { it.isTextual }?.asText() ?: return null
        val parsed = PluginCrypto.parseSignatureHeader(sigHeader) ?: return null
        val pub = runCatching { Base64.getDecoder().decode(pubB64) }.getOrNull() ?: return null
        return RotationAnnouncement(
            newKeyId = newKeyId,
            newPublicKey = pub,
            validFrom = validFrom,
            signedByKeyId = parsed.keyId,
            signature = parsed.signature
        )
    }

    /**
     * Production compatibility surface: every theme/API engine at contract v1.
     * SCRIPT is deliberately absent — script manifests stay INCOMPATIBLE until the
     * Phase 3 sandbox + blocking security review land.
     */
    fun productionCapabilities(appVersion: String): HostCapabilities = HostCapabilities(
        appVersion = appVersion,
        supportedEngines = mapOf(
            SourceEngine.MADARA to 1..1,
            SourceEngine.MANGAREADER to 1..1,
            SourceEngine.ASTRO to 1..1,
            SourceEngine.API to 1..1
        ),
        supportedBridgeApi = 1
    )
}
