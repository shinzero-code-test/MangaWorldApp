package com.exapps.mangaworld.core.source.plugins

/**
 * Phase 2B distribution policy: where remote payloads may come from.
 *
 * The dashboard origin is the only approved distributor. Manifest URLs must be
 * https and same-host with the index they were discovered through — a swapped
 * URL (even to another https host) fails closed. Localhost/http is never
 * approved outside tests (see `allowInsecure`, test-only).
 *
 * Pure JVM-safe.
 */
object PluginDistribution {

    /** Production dashboard origin serving `/plugins/index.json`. */
    const val DEFAULT_BASE_URL = "https://mangaworld-admin.vercel.app/plugins"

    const val INDEX_PATH = "index.json"

    fun indexUrl(baseUrl: String = DEFAULT_BASE_URL): String =
        "${baseUrl.trimEnd('/')}/$INDEX_PATH"

    /**
     * Approval gate for a manifest URL discovered via [indexUrl].
     * Same-host + https required. Returns null when approved, reason otherwise.
     */
    fun checkManifestUrl(
        manifestUrl: String,
        indexUrl: String,
        allowInsecure: Boolean = false
    ): String? {
        val manifest = runCatching { java.net.URI(manifestUrl.trim()) }.getOrNull()
            ?: return "unparseable manifestUrl"
        val index = runCatching { java.net.URI(indexUrl.trim()) }.getOrNull()
            ?: return "unparseable indexUrl"
        val secure = manifest.scheme.equals("https", ignoreCase = true) ||
            (allowInsecure && manifest.scheme.equals("http", ignoreCase = true))
        if (!secure) return "manifestUrl must be https"
        if (manifest.host.isNullOrBlank()) return "manifestUrl has no host"
        if (!manifest.host.equals(index.host, ignoreCase = true)) {
            return "manifestUrl host must match index host"
        }
        return null
    }

    /**
     * `kind` (index hint) vs `engine` (signed manifest) skew rule (plan §6):
     * the manifest always wins; a mismatch is logged for dashboard forensics,
     * never fatal — the signature already authenticated the payload.
     */
    fun kindEngineSkew(kind: String, engine: SourceEngine): Boolean {
        val hinted = when (kind.lowercase()) {
            "descriptor" -> setOf(
                SourceEngine.MADARA, SourceEngine.MANGAREADER,
                SourceEngine.ASTRO, SourceEngine.API
            )
            "script" -> setOf(SourceEngine.SCRIPT)
            "builtin" -> setOf(SourceEngine.CUSTOM)
            else -> return true
        }
        return engine !in hinted
    }
}
