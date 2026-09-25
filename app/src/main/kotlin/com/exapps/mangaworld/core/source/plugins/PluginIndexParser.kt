package com.exapps.mangaworld.core.source.plugins

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Phase 2B `index.json` parser: the ONLY polled artifact, and an untrusted
 * discovery hint — it can never authorise installation (see
 * [PluginStorage.authorizeInstall]).
 *
 * Mini-schema (plan §6): `{schemaVersion, updatedAt, entries[]}` with
 * `{id, version, kind, minAppVersion, manifestUrl}` per entry. Hard fail-closed
 * limits: byte cap + entry cap (several hundred sources fit comfortably; no
 * pagination until a cap is actually approached).
 *
 * Pure JVM-safe (Jackson tree-model only).
 */
object PluginIndexParser {

    const val MAX_INDEX_BYTES = 64 * 1024
    const val MAX_ENTRIES = 1000
    const val SCHEMA_VERSION = 1

    private val mapper = ObjectMapper()

    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val APP_VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")

    sealed interface IndexResult {
        data class Valid(
            val updatedAt: String,
            val entries: List<PluginIndexEntry>
        ) : IndexResult

        data class Invalid(val reason: String) : IndexResult
    }

    fun parse(bytes: ByteArray): IndexResult {
        if (bytes.size > MAX_INDEX_BYTES) {
            return IndexResult.Invalid("index exceeds $MAX_INDEX_BYTES bytes")
        }
        val root = runCatching { mapper.readTree(bytes) }.getOrNull()
            ?: return IndexResult.Invalid("unparseable index")
        if (!root.isObject) return IndexResult.Invalid("top level must be an object")
        if (root.get("schemaVersion")?.takeIf { it.isInt }?.asInt() != SCHEMA_VERSION) {
            return IndexResult.Invalid("unsupported schemaVersion")
        }
        val updatedAt = root.get("updatedAt")?.takeIf { it.isTextual }?.asText()
            ?: return IndexResult.Invalid("missing updatedAt")
        val entriesNode = root.get("entries")?.takeIf { it.isArray }
            ?: return IndexResult.Invalid("missing entries")
        if (entriesNode.size() > MAX_ENTRIES) {
            return IndexResult.Invalid("too many entries")
        }
        val entries = mutableListOf<PluginIndexEntry>()
        val seen = mutableSetOf<String>()
        for (node in entriesNode) {
            val id = node.get("id")?.takeIf { it.isTextual }?.asText()
                ?.takeIf { ID_REGEX.matches(it) }
                ?: return IndexResult.Invalid("bad entry id")
            // First wins on duplicates; a forged bump rides the same manifest
            // verification as any other candidate (authorizeInstall binds id+version).
            if (!seen.add(id)) continue
            val version = node.get("version")?.takeIf { it.isInt }?.asInt()
                ?.takeIf { it >= 1 }
                ?: return IndexResult.Invalid("bad entry version for $id")
            val kind = node.get("kind")?.takeIf { it.isTextual }?.asText()
                ?.takeIf { it.isNotBlank() && it.length <= 32 }
                ?: return IndexResult.Invalid("bad entry kind for $id")
            val minAppVersion = node.get("minAppVersion")?.takeIf { it.isTextual }?.asText()
                ?.takeIf { APP_VERSION_REGEX.matches(it) }
                ?: return IndexResult.Invalid("bad entry minAppVersion for $id")
            val manifestUrl = node.get("manifestUrl")?.takeIf { it.isTextual }?.asText()
                ?.takeIf { it.isNotBlank() && it.length <= 512 }
                ?: return IndexResult.Invalid("bad entry manifestUrl for $id")
            entries += PluginIndexEntry(
                id = id,
                version = version,
                manifestUrl = manifestUrl,
                minAppVersion = minAppVersion,
                kind = kind
            )
        }
        return IndexResult.Valid(updatedAt = updatedAt, entries = entries)
    }
}
