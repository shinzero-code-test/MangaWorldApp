package com.exapps.mangaworld.core.source.plugins

import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2A local plugin store: staging → verify → immutable activation.
 *
 * Implements the [PluginStorage] pure-logic contract with real I/O:
 * - Candidates are written to `.staging`, fully verified (size → strict parse →
 *   JCS canonicalize → Ed25519 → compat gate → schema), smoke-tested, then moved
 *   to immutable `versions/<n>/`. Staging is never visible to the registry.
 * - Activation moves *pointers* ([PluginStorage.activate]) in the index; the
 *   previous verified version is retained for [rollback] (pointer restore, no copy).
 * - Same-version reinstall with different bytes is refused (immutability).
 * - [enforceFreshness] applies the trust-age gate to *downloaded* payloads only;
 *   APK-bundled pilots skip it (APK signature is their trust anchor).
 *
 * No Android APIs (java.io only) — JVM-testable with a temp dir + fake index.
 */
@Singleton
class PluginStore @Inject constructor(
    private val index: PluginIndexStore,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    /** Outcome of an install attempt. Fail-closed: any gate refuses the candidate. */
    sealed interface InstallResult {
        data class Installed(val manifest: PluginManifest, val isUpdate: Boolean) : InstallResult
        data class Rejected(val reason: ManifestInvalidReason, val message: String) : InstallResult
    }

    /**
     * Verifies and activates one manifest payload.
     *
     * @param baseDir root (`files/plugins` in prod, temp dir in tests).
     * @param manifestBytes raw `plugin.json` bytes (signature included).
     * @param origin trust tier of this payload.
     * @param trustedKeys keyId → raw public key (pinned + RC-merged by the caller).
     * @param host host capabilities for the compat gate.
     * @param enforceFreshness true for downloaded payloads (trust age vs issuedAt).
     * @param smoke post-verification activation smoke; false blocks activation.
     */
    suspend fun install(
        baseDir: File,
        manifestBytes: ByteArray,
        origin: PluginOrigin,
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        enforceFreshness: Boolean = true,
        smoke: (PluginManifest) -> Boolean = { true }
    ): InstallResult = withContext(io) {
        val parser = ManifestParser(trustedKeys = trustedKeys, host = host)
        val valid = when (val r = parser.parseAndVerify(manifestBytes)) {
            is ManifestResult.Valid -> r
            is ManifestResult.Invalid -> return@withContext InstallResult.Rejected(r.reason, r.message)
        }
        val manifest = valid.manifest
        if (enforceFreshness && !PluginTrust.isFresh(manifest.issuedAt)) {
            return@withContext InstallResult.Rejected(
                ManifestInvalidReason.SCHEMA_VIOLATION, "stale trust anchor issuedAt"
            )
        }
        if (!smoke(manifest)) {
            return@withContext InstallResult.Rejected(
                ManifestInvalidReason.SCHEMA_VIOLATION, "activation smoke failed"
            )
        }
        val id = manifest.id.value
        val versionDir = File(PluginStorage.versionDir(baseDir.path, id, manifest.version))
        if (versionDir.isDirectory) {
            // Immutable versions: same bytes = idempotent success, different bytes = refuse.
            val existing = File(versionDir, "plugin.json").takeIf { it.isFile }?.readBytes()
            if (existing != null && !existing.contentEquals(manifestBytes)) {
                return@withContext InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "version immutable: bytes differ"
                )
            }
        } else {
            // Stage → move. Partial/corrupt staging never becomes visible.
            val staging = File(PluginStorage.stagingDir(baseDir.path, id))
            staging.mkdirs()
            val staged = File(staging, "plugin.json")
            staged.writeBytes(manifestBytes)
            versionDir.mkdirs()
            val target = File(versionDir, "plugin.json")
            if (!staged.renameTo(target)) {
                staged.copyTo(target, overwrite = true)
                staged.delete()
            }
            deleteRecursively(staging)
        }
        val prev = index.get(id)
        val next = PluginStorage.activate(
            PluginStorage.ActivationState(prev?.activeVersion, prev?.previousVersion),
            manifest.version
        )
        index.put(
            PluginIndexRecord(
                id = id,
                activeVersion = next.active,
                previousVersion = next.previous,
                origin = origin,
                status = PluginStatus.INSTALLED,
                manifestJson = manifestBytes.toString(Charsets.UTF_8)
            )
        )
        InstallResult.Installed(manifest, isUpdate = prev?.activeVersion != null)
    }

    /** Pointer-only rollback to the previous verified version. No byte copying. */
    suspend fun rollback(id: String): PluginIndexRecord? = withContext(io) {
        val current = index.get(id) ?: return@withContext null
        val next = PluginStorage.rollback(
            PluginStorage.ActivationState(current.activeVersion, current.previousVersion)
        )
        if (next == PluginStorage.ActivationState(current.activeVersion, current.previousVersion)) {
            return@withContext current
        }
        val updated = current.copy(
            activeVersion = next.active,
            previousVersion = next.previous,
            status = PluginStatus.INSTALLED,
            updatedAt = System.currentTimeMillis()
        )
        index.put(updated)
        updated
    }

    /**
     * Quota enforcement over on-disk versions. Active/previous pointers are never
     * evicted — over-budget then stays over-budget and is reported.
     */
    suspend fun evictIfOverBudget(
        baseDir: File,
        budgetBytes: Long
    ): PluginStorage.EvictionPlan = withContext(io) {
        val records = index.getAll()
        val active = records.mapNotNull { r -> r.activeVersion?.let { SourceId(r.id) to it } }.toMap()
        val previous = records.mapNotNull { r -> r.previousVersion?.let { SourceId(r.id) to it } }.toMap()
        val stored = baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
            .flatMap { idDir ->
                File(idDir, "versions").listFiles()?.filter { it.isDirectory }.orEmpty()
                    .mapNotNull { vDir ->
                        val version = vDir.name.toIntOrNull() ?: return@mapNotNull null
                        val bytes = vDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        PluginStorage.StoredVersion(SourceId(idDir.name), version, bytes)
                    }
            }
        val plan = PluginStorage.selectEvictable(stored, active, previous, budgetBytes)
        for (v in plan.evict) {
            deleteRecursively(File(PluginStorage.versionDir(baseDir.path, v.id.value, v.version)))
        }
        plan
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
