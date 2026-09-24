package com.exapps.mangaworld.core.source.plugins

/**
 * Phase 0 plugin-contract model: frozen `plugin.json` schema v1 vocabulary.
 *
 * Pure Kotlin — no Android dependencies, so the whole contract (and its tests)
 * executes on plain JVM CI. Nothing here is wired into production flows yet;
 * Phase 1 introduces `SourcePlugin`/`SourceRegistry` on top of these types.
 *
 * Cross-platform note (dashboard/Node): the manifest is parsed strictly (duplicate
 * keys rejected), canonicalized with JCS (RFC 8785) *after* removing `signature`,
 * and only then trusted. Any implementation vibrating from this order (parse →
 * canonicalize → verify → validate) will disagree on bytes with the others.
 */

/** Stable, persisted identity. Equal to the `sourceId` strings Room/DataStore already store. */
@JvmInline
value class SourceId(val value: String)

/** Closed engine vocabulary. A manifest naming anything else fails validation. */
enum class SourceEngine(val serialName: String) {
    MADARA("madara"),
    MANGAREADER("mangareader"),
    ASTRO("astro"),
    API("api"),
    SCRIPT("script"),

    /**
     * Built-in Kotlin scrapers with site-specific logic (no remote equivalent).
     * Rejected for remote manifests — a downloaded plugin must always name a real engine.
     */
    CUSTOM("custom");

    companion object {
        fun fromSerialName(name: String): SourceEngine? = entries.find { it.serialName == name }
    }
}

/** Where a plugin payload came from. Drives trust, update and badge behavior. */
enum class PluginOrigin { OFFICIAL, CUSTOM, LOCAL }

/**
 * Explicit lifecycle states (§11A). Only [ENABLED] is eligible for normal source
 * selection — every other state must render as unavailable with its own reason.
 */
enum class PluginStatus {
    AVAILABLE,
    INSTALLED,
    ENABLED,
    DISABLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    INVALID,
    QUARANTINED,
    REVOKED
}

/**
 * Validated `plugin.json` (schema v1). Instances only ever come from
 * [ManifestParser.parseAndVerify] — never constructed from untrusted input directly.
 */
data class PluginManifest(
    val id: SourceId,
    val version: Int,
    val minAppVersion: String,
    /** Trust-age anchor (ISO-8601). Sync policy enforces maximum staleness (Phase 2). */
    val issuedAt: String,
    /** Required for SCRIPT, rejected on every other engine. */
    val bridgeApi: Int?,
    val names: Map<String, String>,
    /** Dashboard-relative path or https URL; null = bundled/letter fallback. */
    val logo: String?,
    val engine: SourceEngine,
    /** Required contract version for [engine]. */
    val engineApi: Int,
    val baseUrl: String,
    val requiresVerification: Boolean,
    val enabledByDefault: Boolean,
    /**
     * Distribution-policy gate (robots-gated APIs etc.). When true the source must never
     * auto-enable or auto-update: install/update surface it for explicit user opt-in and the
     * sync layer treats it as consent-required. Optional, defaults to false.
     */
    val requiresPermission: Boolean = false,
    /** Engine deviations only; keys whitelisted per engine (see ManifestParser). */
    val config: Map<String, String>,
    /** API-engine only: nested `paths` string map. */
    val apiPaths: Map<String, String>,
    /** Normalized lowercase literal hosts (v1: no wildcards). Base host is implied. */
    val allowedHosts: Set<String>,
    val timeoutMs: Int,
    val maxResponseMb: Int,
    /** Lowercase hex sha256 of `source.js`; SCRIPT only. */
    val scriptSha256: String?,
    /** Key id that signed this manifest (audit trail). */
    val signatureKeyId: String
) {
    /** Hosts this plugin may request: base host always implied, plus declared list. */
    val effectiveHosts: Set<String>
        get() = allowedHosts + HostPolicy.hostOf(baseUrl)
}

/** Untrusted discovery hint from `index.json`. Authorises nothing on its own. */
data class PluginIndexEntry(
    val id: String,
    val version: Int,
    val manifestUrl: String,
    val minAppVersion: String
)

/** Why a manifest was rejected. Fails closed: any reason blocks activation. */
enum class ManifestInvalidReason {
    TOO_LARGE,
    PARSE_ERROR,
    DUPLICATE_KEYS,
    MISSING_SIGNATURE,
    MALFORMED_SIGNATURE,
    UNKNOWN_KEY_ID,
    BAD_SIGNATURE,
    INCOMPATIBLE,
    SCHEMA_VIOLATION
}

/** Outcome of the full parse → canonicalize → verify → validate pipeline. */
sealed interface ManifestResult {
    data class Valid(
        val manifest: PluginManifest,
        /** Exact canonical bytes that were signed. */
        val canonicalBytes: ByteArray,
        val keyId: String
    ) : ManifestResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Valid) return false
            return manifest == other.manifest && canonicalBytes.contentEquals(other.canonicalBytes) &&
                keyId == other.keyId
        }

        override fun hashCode(): Int {
            var result = manifest.hashCode()
            result = 31 * result + canonicalBytes.contentHashCode()
            result = 31 * result + keyId.hashCode()
            return result
        }
    }

    data class Invalid(val reason: ManifestInvalidReason, val message: String) : ManifestResult
}

/** Host capabilities a manifest is checked against (app + engine surface). */
data class HostCapabilities(
    val appVersion: String,
    /** Supported contract range per engine; absent engine = unsupported. */
    val supportedEngines: Map<SourceEngine, IntRange>,
    val supportedBridgeApi: Int
)

/** Outcome of the compatibility gate (runs after signature verification). */
sealed interface CompatibilityResult {
    data object Compatible : CompatibilityResult
    data class Incompatible(val reason: String) : CompatibilityResult
}
