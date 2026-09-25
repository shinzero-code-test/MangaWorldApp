package com.exapps.mangaworld.core.source.sync

/**
 * Phase 2B fetch port: tests fake it, production uses [OkHttpPluginFetcher].
 * Bodies are always size-capped; hosts always allow-listed per hop.
 */
interface PluginFetcher {
    data class FetchResult(
        val body: ByteArray,
        /** Final URL after validated redirects (for audit logs, never trust). */
        val finalUrl: String,
        /** Response `ETag` for conditional polling (null when absent). */
        val etag: String? = null,
        val fromCache: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FetchResult) return false
            return body.contentEquals(other.body) && finalUrl == other.finalUrl &&
                etag == other.etag && fromCache == other.fromCache
        }

        override fun hashCode(): Int {
            var result = body.contentHashCode()
            result = 31 * result + finalUrl.hashCode()
            result = 31 * result + (etag?.hashCode() ?: 0)
            result = 31 * result + fromCache.hashCode()
            return result
        }
    }

    /** Failure modes — all fail closed, mapped to sync outcomes upstream. */
    sealed class FetchFailure(message: String) : Exception(message) {
        class TooLarge(val bytes: Long) : FetchFailure("payload exceeds cap ($bytes bytes)")
        class Http(val code: Int, val url: String) : FetchFailure("HTTP $code from $url")
        class RedirectRejected(val reason: String) :
            FetchFailure("redirect rejected: $reason")

        class Insecure(val url: String) : FetchFailure("refusing insecure URL")
        class Network(cause: Throwable) : FetchFailure("network error: ${cause.message}")
    }

    /**
     * GET with manual redirect validation.
     *
     * @param allowedHosts effective hosts (base + declared list) for the destination.
     * @param maxBytes hard body cap (index/manifest budgets).
     * @param headers extra headers (e.g. `If-None-Match`); `Cookie` is managed
     *   internally per hop and never forwarded across hosts by the caller.
     */
    suspend fun get(
        url: String,
        allowedHosts: Set<String>,
        maxBytes: Long,
        headers: Map<String, String> = emptyMap()
    ): FetchResult
}
