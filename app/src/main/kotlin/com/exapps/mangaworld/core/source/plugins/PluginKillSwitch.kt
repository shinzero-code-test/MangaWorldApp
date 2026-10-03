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

    /** Parses the RC payload; any malformation fails closed to [Policy.EMPTY]. */
    fun parse(rcJson: String?): Policy {
        if (rcJson.isNullOrBlank()) return Policy.EMPTY
        if (rcJson.toByteArray(Charsets.UTF_8).size > MAX_KILLSWITCH_JSON_BYTES) return Policy.EMPTY
        return runCatching {
            val root = mapper.readTree(rcJson)
            if (!root.isObject) return Policy.EMPTY
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
            Policy(revoked = revoked, disabledEngines = engines, disableAllCustoms = customs)
        }.getOrDefault(Policy.EMPTY)
    }

    /** True when [id]/[version] is revoked (null version revokes all versions). */
    fun isRevoked(policy: Policy, id: String, version: Int?): Boolean =
        policy.revoked.any { it.id == id && (it.version == null || it.version == version) }
}
