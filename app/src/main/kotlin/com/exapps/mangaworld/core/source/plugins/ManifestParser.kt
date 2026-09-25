package com.exapps.mangaworld.core.source.plugins

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.erdtman.jcs.JsonCanonicalizer
import java.time.Instant

/**
 * Full `plugin.json` pipeline: size-cap → strict parse (duplicate keys rejected) →
 * signature extraction → JCS canonicalization (signature excluded) → Ed25519 verification →
 * compatibility gate → strict schema validation.
 *
 * Ordering is load-bearing and cross-platform: the dashboard signer MUST perform the same
 * strict-parse → JCS → sign sequence (documented in the plan's canonical-signing spec), so
 * both sides agree byte-for-byte. Verification happens before any optional field
 * (`engine`, `config`, `allowedHosts`) is trusted.
 *
 * Jackson is used tree-model-only (no databind reflection → no R8 keep-rules needed).
 * Pure JVM-safe.
 *
 * @param trustedKeys keyId → raw 32-byte Ed25519 public key. Several ids may coexist
 *   during a rotation window; anything else is [ManifestInvalidReason.UNKNOWN_KEY_ID].
 * @param maxBytes manifest size cap (DoS bound before parsing).
 * @param host capabilities for the compatibility gate.
 */
class ManifestParser(
    private val trustedKeys: Map<String, ByteArray>,
    private val maxBytes: Int = MAX_MANIFEST_BYTES,
    private val host: HostCapabilities
) {

    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    fun parseAndVerify(bytes: ByteArray): ManifestResult {
        if (bytes.size > maxBytes) {
            return ManifestResult.Invalid(ManifestInvalidReason.TOO_LARGE, "manifest exceeds $maxBytes bytes")
        }
        val root = try {
            mapper.readTree(bytes)
        } catch (e: Exception) {
            // STRICT_DUPLICATE_DETECTION surfaces as "Duplicate field 'x'".
            val dup = e.message?.contains("Duplicate field") == true
            return ManifestResult.Invalid(
                if (dup) ManifestInvalidReason.DUPLICATE_KEYS else ManifestInvalidReason.PARSE_ERROR,
                (e.message ?: "unparseable").take(200)
            )
        }
        if (!root.isObject) {
            return ManifestResult.Invalid(ManifestInvalidReason.PARSE_ERROR, "top level must be an object")
        }
        val obj = root as ObjectNode

        val signatureHeader = obj.get("signature")?.takeIf { it.isTextual }?.asText()
            ?: return ManifestResult.Invalid(ManifestInvalidReason.MISSING_SIGNATURE, "no signature field")
        val parsed = PluginCrypto.parseSignatureHeader(signatureHeader)
            ?: return ManifestResult.Invalid(ManifestInvalidReason.MALFORMED_SIGNATURE, "bad signature shape")
        val publicKey = trustedKeys[parsed.keyId]
            ?: return ManifestResult.Invalid(
                ManifestInvalidReason.UNKNOWN_KEY_ID, "unknown key ${parsed.keyId}"
            )

        // Canonicalize WITHOUT the signature field. The tree was already strict-parsed
        // (no duplicate keys), so re-serialization into the canonicalizer is faithful:
        // key order, escapes and number spellings are all normalized by JCS itself.
        obj.remove("signature")
        val canonical = try {
            JsonCanonicalizer(mapper.writeValueAsString(obj)).getEncodedString()
        } catch (e: Exception) {
            return ManifestResult.Invalid(
                ManifestInvalidReason.PARSE_ERROR, "canonicalization failed: ${(e.message ?: "").take(120)}"
            )
        }
        val canonicalBytes = canonical.toByteArray(Charsets.UTF_8)
        if (!PluginCrypto.verify(publicKey, canonicalBytes, parsed.signature)) {
            return ManifestResult.Invalid(ManifestInvalidReason.BAD_SIGNATURE, "signature mismatch")
        }

        // Compatibility BEFORE full schema: an old app faced with a newer manifest must
        // decline cleanly as INCOMPATIBLE, not trip on unknown fields as a schema error.
        // Unknown engine NAMES are a vocabulary violation (closed set), not a version gap.
        val engine = obj.get("engine")?.takeIf { it.isTextual }?.asText()
            ?.let { SourceEngine.fromSerialName(it) }
            ?.takeIf { it != SourceEngine.CUSTOM }
            ?: return ManifestResult.Invalid(
                ManifestInvalidReason.SCHEMA_VIOLATION, "unknown engine"
            )
        val compat = EngineCompatibility.check(
            engine = engine,
            engineApi = obj.get("engineApi")?.takeIf { it.isInt }?.asInt(),
            bridgeApi = obj.get("bridgeApi")?.takeIf { it.isInt }?.asInt(),
            minAppVersion = obj.get("minAppVersion")?.takeIf { it.isTextual }?.asText(),
            host = host
        )
        if (compat is CompatibilityResult.Incompatible) {
            return ManifestResult.Invalid(ManifestInvalidReason.INCOMPATIBLE, compat.reason)
        }

        return validateSchema(obj, engine, parsed.keyId, canonicalBytes)
    }

    // ─── Strict schema (v1) ────────────────────────────────────────────────

    private fun validateSchema(
        obj: ObjectNode,
        engine: SourceEngine,
        keyId: String,
        canonicalBytes: ByteArray
    ): ManifestResult {
        // Fail closed on unknown top-level fields: catches typos ("engin", "base_uri")
        // instead of silently ignoring them. (Forward-compat is handled by the
        // minAppVersion gate above — old apps never reach strict validation on v2.)
        val known = KNOWN_FIELDS
        obj.fieldNames().forEach { name ->
            if (name !in known) {
                return ManifestResult.Invalid(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "unknown field '$name'"
                )
            }
        }

        fun text(name: String): String? = obj.get(name)?.takeIf { it.isTextual }?.asText()
        fun fail(msg: String): ManifestResult.Invalid =
            ManifestResult.Invalid(ManifestInvalidReason.SCHEMA_VIOLATION, msg)

        val id = text("id")?.takeIf { ID_REGEX.matches(it) }
            ?: return fail("bad id")
        val version = obj.get("version")?.takeIf { it.isInt }?.asInt()?.takeIf { it >= 1 }
            ?: return fail("bad version")
        val minAppVersion = text("minAppVersion")?.takeIf { APP_VERSION_REGEX.matches(it) }
            ?: return fail("bad minAppVersion")
        val issuedAt = text("issuedAt") ?: return fail("missing issuedAt")
        try {
            Instant.parse(issuedAt)
        } catch (_: Exception) {
            return fail("bad issuedAt")
        }

        val bridgeApi = obj.get("bridgeApi")?.takeIf { it.isInt }?.asInt()
        if (engine == SourceEngine.SCRIPT) {
            if (bridgeApi == null || bridgeApi < 1) return fail("script requires bridgeApi >= 1")
        } else if (obj.has("bridgeApi")) {
            return fail("bridgeApi is script-only")
        }

        val names = obj.get("names")?.takeIf { it.isObject }?.let { node ->
            node.fields().asSequence().toList()
        }?.takeIf { it.isNotEmpty() }?.associate { (k, v) ->
            if (!LOCALE_KEY_REGEX.matches(k) || !v.isTextual || v.asText().isBlank() ||
                v.asText().length > MAX_NAME_LENGTH
            ) {
                return fail("bad names entry '$k'")
            }
            k to v.asText()
        } ?: return fail("bad names")

        val logo = text("logo")
        // Host-checked after allowedHosts parse below (absolute URLs must stay
        // inside the manifest's own host set or the Cloudinary pipeline).

        val engineApi = obj.get("engineApi")?.takeIf { it.isInt }?.asInt()?.takeIf { it >= 1 }
            ?: return fail("bad engineApi")

        val baseUrl = text("baseUrl") ?: return fail("missing baseUrl")
        val baseHost = validateBaseUrl(baseUrl) ?: return fail("bad baseUrl")

        val requiresVerification = obj.get("requiresVerification")?.takeIf { it.isBoolean }?.asBoolean()
            ?: return fail("bad requiresVerification")
        val enabledByDefault = obj.get("enabledByDefault")?.takeIf { it.isBoolean }?.asBoolean()
            ?: return fail("bad enabledByDefault")
        val requiresPermission = obj.get("requiresPermission")?.let {
            if (!it.isBoolean) return fail("bad requiresPermission")
            it.asBoolean()
        } ?: false

        val configNode = obj.get("config")
        if (configNode != null && !configNode.isObject) return fail("bad config")
        val configWhitelist = CONFIG_KEYS[engine].orEmpty()
        val config = mutableMapOf<String, String>()
        configNode?.fields()?.forEach { (k, v) ->
            if (k !in configWhitelist || !v.isTextual) return fail("bad config key '$k'")
            config[k] = v.asText()
        }

        // API-engine only nested string map; rejected everywhere else.
        var apiPaths: Map<String, String> = emptyMap()
        if (engine == SourceEngine.API) {
            val paths = obj.get("paths")?.takeIf { it.isObject }
                ?: return fail("api engine requires paths")
            apiPaths = paths.fields().asSequence().toList()
                .takeIf { it.isNotEmpty() }?.associate { (k, v) ->
                    if (!v.isTextual || v.asText().isBlank()) return fail("bad paths entry '$k'")
                    k to v.asText()
                } ?: return fail("bad paths")
        } else if (obj.has("paths")) {
            return fail("paths is api-only")
        }

        val hostsNode = obj.get("allowedHosts")?.takeIf { it.isArray }
            ?: return fail("bad allowedHosts")
        if (hostsNode.isEmpty || hostsNode.size() > MAX_HOSTS) return fail("bad allowedHosts size")
        val allowedHosts = mutableSetOf<String>()
        hostsNode.forEach { h ->
            val raw = h.takeIf { it.isTextual }?.asText() ?: return fail("bad allowedHosts entry")
            if (!HostPolicy.isValidAllowListEntry(raw)) return fail("bad allowedHosts entry '$raw'")
            allowedHosts += raw.lowercase()
        }

        val timeoutMs = obj.get("timeoutMs")?.takeIf { it.isInt }?.asInt()
            ?.takeIf { it in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS }
            ?: return fail("bad timeoutMs")
        val maxResponseMb = obj.get("maxResponseMb")?.takeIf { it.isInt }?.asInt()
            ?.takeIf { it in MIN_RESPONSE_MB..MAX_RESPONSE_MB }
            ?: return fail("bad maxResponseMb")

        // Logo host constraint (plan §12): relative dashboard paths stay as-is;
        // absolute URLs must live inside the manifest's own host set (base host
        // implied) or the Cloudinary image pipeline — image parsers are not a
        // trusted input surface for off-allowlist hosts.
        if (logo != null && !isValidLogoRef(logo, allowedHosts + baseHost)) {
            return fail("bad logo")
        }

        val scriptSha = text("scriptSha256")
        if (engine == SourceEngine.SCRIPT) {
            if (scriptSha == null || !SHA256_HEX_REGEX.matches(scriptSha)) {
                return fail("script requires scriptSha256")
            }
        } else if (scriptSha != null) {
            return fail("scriptSha256 is script-only")
        }

        return ManifestResult.Valid(
            manifest = PluginManifest(
                id = SourceId(id),
                version = version,
                minAppVersion = minAppVersion,
                issuedAt = issuedAt,
                bridgeApi = bridgeApi,
                names = names,
                logo = logo,
                engine = engine,
                engineApi = engineApi,
                baseUrl = baseUrl,
                requiresVerification = requiresVerification,
                enabledByDefault = enabledByDefault,
                requiresPermission = requiresPermission,
                config = config,
                apiPaths = apiPaths,
                allowedHosts = allowedHosts,
                timeoutMs = timeoutMs,
                maxResponseMb = maxResponseMb,
                scriptSha256 = scriptSha?.lowercase(),
                signatureKeyId = keyId
            ),
            canonicalBytes = canonicalBytes,
            keyId = keyId
        ).also {
            // Base host is implied at match time (effectiveHosts); nothing to validate beyond baseUrl.
            check(baseHost.isNotEmpty())
        }
    }

    private fun validateBaseUrl(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.query != null || uri.fragment != null) return null
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        if (host.isEmpty() || !HostPolicy.isValidAllowListEntry(host)) return null
        return host
    }

    private fun isValidLogoRef(logo: String, hosts: Set<String>): Boolean {
        if (logo.isBlank() || logo.length > 512) return false
        if (".." in logo.split("/")) return false
        // Either a dashboard-relative path or an https URL on the allow-list.
        if ("://" in logo) {
            val host = validateBaseUrl(logo) ?: return false
            return host in hosts || host in CLOUDINARY_HOSTS
        }
        return !logo.startsWith("/")
    }

    companion object {
        /** Image-pipeline hosts permitted for absolute logo URLs. */
        val CLOUDINARY_HOSTS = setOf("res.cloudinary.com")
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_HOSTS = 16
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 120_000
        const val MIN_RESPONSE_MB = 1
        const val MAX_RESPONSE_MB = 50
        const val MAX_NAME_LENGTH = 100

        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val APP_VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")
        private val LOCALE_KEY_REGEX = Regex("^[A-Za-z]{2,8}(-[A-Za-z]{2,8})?$")
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

        private val KNOWN_FIELDS = setOf(
            "id", "version", "minAppVersion", "issuedAt", "bridgeApi", "names", "logo",
            "engine", "engineApi", "baseUrl", "requiresVerification", "enabledByDefault",
            "requiresPermission",
            "config", "paths", "allowedHosts", "timeoutMs", "maxResponseMb",
            "scriptSha256", "signature"
        )

        /** Engine deviations only — anything else in `config` is a schema violation. */
        private val THEME_CONFIG_KEYS = setOf(
            "ajaxChapters", "chapterListSelector", "lazyImageAttrs",
            "featuredSelector", "latestSelector", "detailSelector", "pageImageSelector",
            // Phase 1 standard vocabulary (PluginConfigKeys): chapter-list strategy,
            // image-Referer policy, archive path, search action. Builtin descriptors
            // already carry these — remote manifests must accept the same set.
            "chapterListStrategy", "chapterAjaxPath", "chapterAction",
            "listPath", "imageRefererPolicy", "searchAction"
        )
        private val API_CONFIG_KEYS = setOf(
            "homeEndpoint", "searchEndpoint", "detailEndpoint", "pagesEndpoint"
        )
        private val CONFIG_KEYS = mapOf(
            SourceEngine.MADARA to THEME_CONFIG_KEYS,
            SourceEngine.MANGAREADER to THEME_CONFIG_KEYS,
            SourceEngine.ASTRO to THEME_CONFIG_KEYS,
            SourceEngine.API to API_CONFIG_KEYS,
            SourceEngine.SCRIPT to emptySet(),
            // Built-in custom scrapers take no remote config; present so the
            // whitelist lookup stays total over the enum.
            SourceEngine.CUSTOM to emptySet()
        )
    }
}
