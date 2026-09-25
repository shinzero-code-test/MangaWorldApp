package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.script.ScriptContract
import com.exapps.mangaworld.core.source.script.ScriptException
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 script install gate: byte cap + hash pin + compile, before any
 * execution or registration.
 *
 * - `source.js` over [ScriptContract.SCRIPT_MAX_BYTES] is refused (independent
 *   of the manifest's `maxResponseMb`, per the schema spec).
 * - `sha256(source.js)` must equal the manifest's `scriptSha256` (lowercase hex);
 *   the check runs here AND at sync download time (defense in depth — the bytes
 *   that execute are always the bytes the signature pinned).
 * - Compilation happens in [ScriptRunnerFactory.create]; syntax errors fail the
 *   install, never a later read path.
 *
 * Pure apart from the injected factory — JVM-testable with a fake fetcher.
 */
@Singleton
class ScriptPluginLoader @Inject constructor(
    private val runnerFactory: ScriptRunnerFactory
) {

    fun load(manifest: PluginManifest, scriptBytes: ByteArray): Result<MangaScraper> {
        if (manifest.engine != SourceEngine.SCRIPT) {
            return Result.failure(IllegalArgumentException("not a script manifest"))
        }
        if (scriptBytes.size > ScriptContract.SCRIPT_MAX_BYTES) {
            return Result.failure(
                ScriptException("source.js exceeds ${ScriptContract.SCRIPT_MAX_BYTES} bytes")
            )
        }
        val expected = manifest.scriptSha256
            ?: return Result.failure(ScriptException("manifest pins no script hash"))
        val actual = sha256Hex(scriptBytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            return Result.failure(ScriptException("script hash mismatch"))
        }
        return runnerFactory.create(manifest, scriptBytes)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Sibling payload for a manifest URL (`…/plugin.json` → `…/source.js`). */
        fun siblingScriptUrl(manifestUrl: String): String? {
            val trimmed = manifestUrl.trim()
            val cut = trimmed.lastIndexOf('/')
            // Must contain a path segment beyond `scheme://host`.
            val schemeEnd = trimmed.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
            if (cut <= schemeEnd) return null
            return trimmed.substring(0, cut + 1) + "source.js"
        }
    }
}
