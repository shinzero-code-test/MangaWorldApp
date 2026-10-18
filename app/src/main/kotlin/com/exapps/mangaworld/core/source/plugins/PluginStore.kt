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

    /**
     * Serializes every payload-directory mutation in-process (v9.3.0, audit B3).
     *
     * Quota eviction walked the version tree and deleted entries with no lock,
     * so a concurrent install could have its version dir removed between the
     * immutability check and the pointer activation — producing exactly the torn
     * directory that left a record claiming a payload that was not on disk.
     * Installs and evictions are rare and short; one mutex is the correct
     * trade-off (per-id keys would still race on the shared index rows).
     */
    private val payloadLock = kotlinx.coroutines.sync.Mutex()

    /** Outcome of an install attempt. Fail-closed: any gate refuses the candidate. */
    sealed interface InstallResult {
        data class Installed(val manifest: PluginManifest, val isUpdate: Boolean) : InstallResult
        data class Rejected(val reason: ManifestInvalidReason, val message: String) : InstallResult
    }

    /**
     * Verification only: signature → freshness → smoke, with no disk or index
     * writes. The sync engine verifies first, applies policy gates, then calls
     * [persistVerified] — a refused candidate never moves the active pointer.
     */
    fun verify(
        manifestBytes: ByteArray,
        trustedKeys: Map<String, ByteArray>,
        host: HostCapabilities,
        enforceFreshness: Boolean = true,
        smoke: (PluginManifest) -> Boolean = { true }
    ): ManifestResult {
        val parser = ManifestParser(trustedKeys = trustedKeys, host = host)
        val valid = when (val r = parser.parseAndVerify(manifestBytes)) {
            is ManifestResult.Valid -> r
            is ManifestResult.Invalid -> return r
        }
        if (enforceFreshness && !PluginTrust.isFresh(valid.manifest.issuedAt)) {
            return ManifestResult.Invalid(
                ManifestInvalidReason.SCHEMA_VIOLATION, "stale trust anchor issuedAt"
            )
        }
        if (!smoke(valid.manifest)) {
            return ManifestResult.Invalid(
                ManifestInvalidReason.SCHEMA_VIOLATION, "activation smoke failed"
            )
        }
        return valid
    }

    /**
     * Persists an already-verified payload: immutable version dir + transactional
     * pointer activation. Fails closed on version-byte conflicts.
     *
     * @param scriptBytes required for SCRIPT manifests (hash re-checked against
     *   the manifest pin — the bytes that execute are always the bytes the
     *   signature pinned); must be null for every other engine.
     */
    suspend fun persistVerified(
        baseDir: File,
        id: String,
        manifest: PluginManifest,
        manifestBytes: ByteArray,
        origin: PluginOrigin,
        scriptBytes: ByteArray? = null
    ): InstallResult = withContext(io) {
        // v9.3.0 (audit B3): serialize against quota eviction so a version dir
        // cannot be deleted between the immutability check and pointer activation.
        payloadLock.withLock {
        if (manifest.engine == SourceEngine.SCRIPT) {
            if (scriptBytes == null) {
                return@withLock InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "script payload missing"
                )
            }
            if (scriptBytes.size > com.exapps.mangaworld.core.source.script.ScriptContract.SCRIPT_MAX_BYTES) {
                return@withLock InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "source.js exceeds byte cap"
                )
            }
            val expected = manifest.scriptSha256
            val actual = ScriptPluginLoader.sha256Hex(scriptBytes)
            if (expected == null || !actual.equals(expected, ignoreCase = true)) {
                return@withLock InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "script hash mismatch"
                )
            }
        } else if (scriptBytes != null) {
            return@withLock InstallResult.Rejected(
                ManifestInvalidReason.SCHEMA_VIOLATION, "unexpected script payload"
            )
        }
        val versionDir = File(PluginStorage.versionDir(baseDir.path, id, manifest.version))
        // Stage → move, ALWAYS. A present-but-incomplete version dir (torn
        // install, or eviction racing an install) previously fell through the
        // branch below with nothing written and then got its pointer activated —
        // leaving a record that claims a version whose payload is not on disk,
        // which is exactly the UNRESOLVED source the user could not recover
        // (audit B2/B3, and the enabler for B6's re-drive). v9.2.5: immutability
        // is still absolute — differing bytes are refused — but an INCOMPLETE
        // dir is repaired from the staged, hash-verified copy.
        val staging = File(PluginStorage.stagingDir(baseDir.path, id))
        staging.mkdirs()
        val staged = File(staging, "plugin.json")
        staged.writeBytes(manifestBytes)
        if (scriptBytes != null) {
            File(staging, "source.js").writeBytes(scriptBytes)
        }
        if (versionDir.isDirectory) {
            // Immutable versions: same bytes = idempotent, different bytes = refuse.
            val existing = File(versionDir, "plugin.json").takeIf { it.isFile }?.readBytes()
            if (existing != null && !existing.contentEquals(manifestBytes)) {
                return@withLock InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "version immutable: bytes differ"
                )
            }
            if (scriptBytes != null) {
                val existingJs = File(versionDir, "source.js").takeIf { it.isFile }?.readBytes()
                // v9.2.5: a MISSING script is repairable (nothing contradicts
                // it); only present-and-differing bytes are an immutability
                // violation. Refusing the missing case made a re-drive unable to
                // ever fix the payload it was called to fix.
                if (existingJs != null && !existingJs.contentEquals(scriptBytes)) {
                    return@withLock InstallResult.Rejected(
                        ManifestInvalidReason.SCHEMA_VIOLATION, "version immutable: script bytes differ"
                    )
                }
            }
        }
        // Partial/corrupt staging never becomes visible: move only complete files.
        versionDir.mkdirs()
        moveInto(staged, File(versionDir, "plugin.json"))
        if (scriptBytes != null) {
            moveInto(File(staging, "source.js"), File(versionDir, "source.js"))
        }
        deleteRecursively(staging)
        // v9.3.0 (audit B2, hard invariant): the pointer is moved ONLY after the
        // bytes it names are verifiably on disk. A record claiming an active
        // version with no payload is the unrecoverable UNRESOLVED state, so the
        // post-condition is checked here rather than trusted to the move.
        val landed = File(versionDir, "plugin.json").takeIf { it.isFile }?.readBytes()
        if (landed == null || !landed.contentEquals(manifestBytes)) {
            return@withLock InstallResult.Rejected(
                ManifestInvalidReason.SCHEMA_VIOLATION, "payload did not land"
            )
        }
        if (manifest.engine == SourceEngine.SCRIPT) {
            val landedJs = File(versionDir, "source.js").takeIf { it.isFile }?.readBytes()
            if (scriptBytes == null || landedJs == null || !landedJs.contentEquals(scriptBytes)) {
                return@withLock InstallResult.Rejected(
                    ManifestInvalidReason.SCHEMA_VIOLATION, "script payload did not land"
                )
            }
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
        when (val v = verify(manifestBytes, trustedKeys, host, enforceFreshness, smoke)) {
            is ManifestResult.Invalid -> return@withContext InstallResult.Rejected(v.reason, v.message)
            is ManifestResult.Valid -> persistVerified(baseDir, v.manifest.id.value, v.manifest, manifestBytes, origin)
        }
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
        // v9.3.0 (audit B3): same lock as persistVerified.
        payloadLock.withLock {
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
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }

    /** Rename-if-possible, else copy + delete. Never leaves a partial target. */
    private fun moveInto(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }
}
