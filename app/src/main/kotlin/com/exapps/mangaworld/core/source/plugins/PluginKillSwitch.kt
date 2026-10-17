package com.exapps.mangaworld.core.source.plugins

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Phase 2B kill-switches (plan §8.11): per-`(id, version)` revocation,
 * engine-level cutoff, and the Lab panic button (`disableAllCustoms`).
 *
 * Transport is Remote Config (`plugin_kill_switch` JSON) — revocation is a
 * fail-closed (disabling) direction, so an authenticated-dashboard transport is
 * sufficient; installation/updates still require signatures either way.
 *
 * Scope discipline (2B): revocation applies to REMOTE/OVERRIDE payloads only.
 * Shipped builtins keep their existing Remote Config `source_<id>_enabled`
 * kill-switch; an engine kill never disables APK-shipped scrapers (that path
 * stays an app release + the per-source flag).
 *
 * Pure JVM-safe.
 */
object PluginKillSwitch {

    /** Remote Config transport key. */
    const val RC_KEY = "plugin_kill_switch"

    data class Revocation(val id: String, val version: Int? = null)

    data class Policy(
        val revoked: List<Revocation> = emptyList(),
        val disabledEngines: Set<SourceEngine> = emptySet(),
        val disableAllCustoms: Boolean = false
    ) {
        companion object {
            val EMPTY = Policy()
        }
    }

    private val mapper = ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    /** C-10 bound: revocation lists are small; unbounded lists cost a Room read per entry per sync. */
    const val MAX_KILLSWITCH_JSON_BYTES = 64 * 1024
    const val MAX_REVOCATIONS = 256
    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    /**
     * Parse outcome, and the difference that matters (v9.2.5, audit B11).
     *
     * "No kill-switch configured" and "kill-switch present but unreadable" are
     * NOT the same thing: both used to collapse to [Policy.EMPTY], so a
     * corrupted or hostile RC payload silently disabled every revocation and
     * engine kill while looking identical to "nothing configured". Callers must
     * refuse to act on [Malformed].
     */
    sealed interface ParseResult {
        data object Absent : ParseResult
        data class Valid(val policy: Policy) : ParseResult
        data class Malformed(val reason: String) : ParseResult
    }

    /**
     * Strict parse for decision points. Absent → no policy; anything present
     * but unreadable → [ParseResult.Malformed] so callers fail closed instead of
     * silently acting as if no kill-switch existed.
     */
    fun parseResult(rcJson: String?): ParseResult {
        if (rcJson.isNullOrBlank()) return ParseResult.Absent
        if (rcJson.toByteArray(Charsets.UTF_8).size > MAX_KILLSWITCH_JSON_BYTES) {
            return ParseResult.Malformed("payload exceeds ${MAX_KILLSWITCH_JSON_BYTES}B")
        }
        return runCatching {
            val root = mapper.readTree(rcJson)
            if (!root.isObject) return ParseResult.Malformed("not an object")
            val revoked = root.get("revoked")?.takeIf { it.isArray }
                ?.takeIf { it.size() <= MAX_REVOCATIONS }
                ?.mapNotNull { node ->
                    val id = node.get("id")?.takeIf { it.isTextual }?.asText()
                        ?.takeIf { ID_REGEX.matches(it) } ?: return@mapNotNull null
                    val version = node.get("version")?.takeIf { it.isInt }?.asInt()
                        ?.takeIf { it >= 1 }
                    Revocation(id, version)
                }.orEmpty()
            val engines = root.get("disabledEngines")?.takeIf { it.isArray }
                ?.mapNotNull { node ->
                    node.takeIf { it.isTextual }?.asText()
                        ?.let { SourceEngine.fromSerialName(it.lowercase()) }
                }?.toSet().orEmpty()
            val customs = root.get("disableAllCustoms")?.takeIf { it.isBoolean }?.asBoolean()
                ?: false
            ParseResult.Valid(
                Policy(revoked = revoked, disabledEngines = engines, disableAllCustoms = customs)
            )
        }.getOrElse { e -> ParseResult.Malformed(e.message?.take(80) ?: e.javaClass.simpleName) }
    }

    /**
     * Convenience parse for non-decision call sites (tests, diagnostics):
     * collapses Absent and Malformed to [Policy.EMPTY]. NEVER use it where a
     * revocation must be enforced — use [parseResult].
     */
    fun parse(rcJson: String?): Policy = when (val r = parseResult(rcJson)) {
        is ParseResult.Valid -> r.policy
        ParseResult.Absent -> Policy.EMPTY
        is ParseResult.Malformed -> Policy.EMPTY
    }

    /** True when [id]/[version] is revoked (null version revokes all versions). */
    fun isRevoked(policy: Policy, id: String, version: Int?): Boolean =
        policy.revoked.any { it.id == id && (it.version == null || it.version == version) }
}
