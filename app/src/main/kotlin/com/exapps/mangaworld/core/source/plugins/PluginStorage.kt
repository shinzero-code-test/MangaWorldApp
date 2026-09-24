package com.exapps.mangaworld.core.source.plugins

/**
 * Versioned on-device storage contract (§11A).
 *
 * Invariants (enforced by construction, tested as pure logic):
 * - Installed versions are immutable: `files/plugins/<id>/versions/<n>/`.
 * - The registry serves exactly one version per id: the Room-selected active pointer.
 * - Candidates live in staging until verified + smoke-tested; staging is never visible.
 * - The previous verified version is retained until the new active passes post-activation
 *   smoke — rollback restores the pointer, never copies bytes.
 * - A hard storage budget bounds accumulation; eviction never touches the active or
 *   rollback-eligible version, no matter how far over budget.
 * - Backups store references (`{id, version, origin}`), never payloads.
 *
 * All functions here are pure (no I/O, no Android) so atomicity is unit-testable.
 * The I/O layer (Phase 2 `RemoteSourceStore`) must implement exactly these transitions.
 */
object PluginStorage {

    /** Directory layout builders. Ids/versions are validated upstream; `..` is still refused. */
    fun versionDir(baseDir: String, id: String, version: Int): String {
        require(!id.contains("..") && !id.contains('/')) { "unsafe plugin id" }
        require(version >= 1) { "version must be >= 1" }
        return "$baseDir/$id/versions/$version"
    }

    fun manifestPath(baseDir: String, id: String, version: Int): String =
        "${versionDir(baseDir, id, version)}/plugin.json"

    fun scriptPath(baseDir: String, id: String, version: Int): String =
        "${versionDir(baseDir, id, version)}/source.js"

    fun stagingDir(baseDir: String, id: String): String {
        require(!id.contains("..") && !id.contains('/')) { "unsafe plugin id" }
        return "$baseDir/$id/.staging"
    }

    /** Room-selected pointer. `previous` is the rollback target (null when none). */
    data class ActivationState(val active: Int?, val previous: Int?)

    /**
     * Transactional activation: the staged candidate becomes active, the outgoing
     * active becomes the rollback target. The candidate MUST already be fully verified
     * (signature + schema + smoke) — this function moves pointers, never payloads.
     */
    fun activate(state: ActivationState, candidate: Int): ActivationState {
        require(candidate >= 1) { "candidate must be >= 1" }
        if (candidate == state.active) return state
        return ActivationState(active = candidate, previous = state.active)
    }

    /** Rollback: restore the previous verified pointer. No-op without a previous version. */
    fun rollback(state: ActivationState): ActivationState {
        val previous = state.previous ?: return state
        return ActivationState(active = previous, previous = null)
    }

    /** One installed version entry for quota accounting. */
    data class StoredVersion(
        val id: SourceId,
        val version: Int,
        val bytes: Long,
        /** True while this version backs an installed-but-disabled plugin, etc. Kept for policy. */
        val inActiveUse: Boolean = false
    )

    data class EvictionPlan(val evict: List<StoredVersion>, val stillOverBudget: Boolean)

    /**
     * Quota enforcement. Eviction candidates are versions that are neither the active
     * nor the rollback-eligible (previous) version of their plugin, oldest first.
     * Active/previous are NEVER evicted — over-budget then stays over-budget and reported.
     */
    fun selectEvictable(
        stored: List<StoredVersion>,
        activeVersions: Map<SourceId, Int>,
        previousVersions: Map<SourceId, Int>,
        budgetBytes: Long
    ): EvictionPlan {
        var total = stored.sumOf { it.bytes }
        if (total <= budgetBytes) return EvictionPlan(emptyList(), false)
        val protected = stored.filter { v ->
            activeVersions[v.id] == v.version || previousVersions[v.id] == v.version
        }.toSet()
        val candidates = stored.filter { it !in protected }.sortedBy { it.version }
        val evict = mutableListOf<StoredVersion>()
        for (v in candidates) {
            if (total <= budgetBytes) break
            evict += v
            total -= v.bytes
        }
        return EvictionPlan(evict, total > budgetBytes)
    }

    /**
     * Install gate: an untrusted `index.json` hint authorises nothing. Installation
     * proceeds only when a manifest verifies AND binds to the hint (id + version equal).
     * Any mismatch — forged bump, URL swap, stale replay — refuses.
     */
    fun authorizeInstall(index: PluginIndexEntry, manifest: ManifestResult): Boolean {
        if (manifest !is ManifestResult.Valid) return false
        return manifest.manifest.id.value == index.id && manifest.manifest.version == index.version
    }
}
