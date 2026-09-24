package com.exapps.mangaworld.core.source.plugins

import java.net.URI

/**
 * Network boundary enforcement for plugins (schema v1).
 *
 * Rules, all fail-closed:
 * - v1 allow-lists are **literal hostnames only**: no wildcards, no userinfo, no ports,
 *   no IPv6 literals (IPv4 literals are allowed when listed literally). A subdomain is
 *   never implied — `cdn.example.com` must be listed even when `example.com` is.
 * - Matching is structural on the parsed host (lowercased, trailing dot stripped), never
 *   substring/contains: `evilexample.com` and `example.com.evil.com` do not match
 *   `example.com`.
 * - Automatic redirect-following stays OFF. Each `Location` is resolved (relative or
 *   absolute) and re-validated before the next hop is issued; hop count is capped and
 *   https→http downgrade at any hop is rejected.
 * - Cookie-bearing requests are restricted to approved hosts by the caller: hop validation
 *   runs *before* credentials are attached, and cookies are re-resolved per hop, never
 *   forwarded from a previous host.
 *
 * Pure JVM-safe (`java.net.URI` only).
 */
object HostPolicy {

    /** Redirect hops allowed per request chain (browsers allow ~20; 8 is plenty for manga CDNs). */
    const val MAX_REDIRECT_HOPS = 8

    /**
     * v1 literal-hostname rule for `allowedHosts` entries. IPv4 literals pass (matched
     * structurally downstream); everything else must be a plain DNS name.
     */
    private val LITERAL_HOST_REGEX =
        Regex("^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")

    /** True when [entry] is legal in an `allowedHosts` list (case-insensitive check). */
    fun isValidAllowListEntry(entry: String): Boolean {
        if (entry.isBlank() || entry.length > 253) return false
        if (entry.contains('*') || entry.contains('/') || entry.contains(':') ||
            entry.contains('@') || entry.any { it.isWhitespace() }
        ) {
            return false
        }
        return LITERAL_HOST_REGEX.matches(entry.lowercase())
    }

    /**
     * Structural host of a URL: lowercased, trailing dot stripped. Empty when the URL
     * has no parseable host (relative URL, opaque URI, parse failure).
     *
     * `java.net.URI.getHost` already strips userinfo and port, so
     * `https://user@evil.com` yields `evil.com` — matched (and rejected) structurally,
     * never via string-contains.
     */
    fun hostOf(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty()
        return host.lowercase().trimEnd('.')
    }

    /** Exact-match membership after normalization. No subdomain implication, no suffix games. */
    fun isHostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false
        return allowedHosts.any { it.lowercase().trimEnd('.') == normalized }
    }

    /** Outcome of validating one redirect hop. */
    sealed interface RedirectDecision {
        /** Safe to request (https URL on the allow-list, within hop budget). */
        data class Follow(val url: String) : RedirectDecision

        /** No `Location` header — end of chain. */
        data object NoRedirect : RedirectDecision

        /** Blocked, with the reason for logs/telemetry (never the raw URL body). */
        data class Reject(val reason: RedirectRejectReason) : RedirectDecision
    }

    enum class RedirectRejectReason {
        EMPTY_LOCATION,
        UNPARSEABLE,
        NOT_HTTPS,
        HOST_NOT_ALLOWED,
        HOP_BUDGET_EXCEEDED
    }

    /**
     * Validates a single redirect hop.
     *
     * @param currentUrl the URL that produced the redirect (relative `Location` resolves here).
     * @param location the raw `Location` header value, or null when absent.
     * @param allowedHosts effective hosts (base host + declared list).
     * @param hopsUsed hops already followed in this chain.
     */
    fun resolveRedirect(
        currentUrl: String,
        location: String?,
        allowedHosts: Set<String>,
        hopsUsed: Int,
        maxHops: Int = MAX_REDIRECT_HOPS
    ): RedirectDecision {
        if (location.isNullOrBlank()) return RedirectDecision.NoRedirect
        if (hopsUsed >= maxHops) return RedirectDecision.Reject(RedirectDecision.RejectReason.HOP_BUDGET_EXCEEDED)
        val next = runCatching {
            URI(currentUrl.trim()).resolve(location.trim())
        }.getOrNull() ?: return RedirectDecision.Reject(RedirectDecision.RejectReason.UNPARSEABLE)
        if (!next.scheme.equals("https", ignoreCase = true)) {
            // Catches both plain-http targets and https→http downgrades mid-chain.
            return RedirectDecision.Reject(RedirectDecision.RejectReason.NOT_HTTPS)
        }
        val host = next.host?.lowercase()?.trimEnd('.').orEmpty()
        if (!isHostAllowed(host, allowedHosts)) {
            return RedirectDecision.Reject(RedirectDecision.RejectReason.HOST_NOT_ALLOWED)
        }
        return RedirectDecision.Follow(next.toASCIIString())
    }
}
