package com.exapps.mangaworld.core.source.plugins

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * C-5: per-hop host gate for descriptor (theme-engine) traffic.
 *
 * Distribution and script fetchers validate every hop against the manifest
 * `effectiveHosts`; the theme path (`DescriptorScraper` → `fetchDocument` on
 * a shared auto-redirect client) had zero references to it — a signed
 * manifest's allow-list was advisory, and OkHttp silently re-sent request
 * headers (including solver/CF cookies) across redirect hops.
 *
 * Installed as a NETWORK interceptor it observes every hop including
 * transparent redirect follow-ups, and fails closed ([IOException]) on:
 * non-https, userinfo, explicit non-default ports, and off-allow-list hosts.
 * Cookie hygiene mirrors plan §4 (validation before credentials): a hop onto
 * a second allowed host never carries the first host's `Cookie` header. The
 * initial hop's host rides a request tag because OkHttp rebuilds follow-up
 * requests internally (tags survive the rebuild; shared interceptor state
 * would leak across calls).
 *
 * Pure JVM-safe (OkHttp only) — unit-tested with a mocked chain.
 */
class HostGateInterceptor(
    private val allowedHosts: Set<String>
) : Interceptor {

    /** First-hop host, carried across OkHttp's internal redirect rebuilds. */
    data class InitialHost(val host: String)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.scheme.equals("https", ignoreCase = true)) {
            throw IOException("descriptor fetch requires https: ${url.host}")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("descriptor fetch rejects userinfo urls")
        }
        if (url.port != -1 && url.port != 443) {
            throw IOException("descriptor fetch rejects non-default port ${url.port}")
        }
        val host = url.host.lowercase().trimEnd('.')
        if (!HostPolicy.isHostAllowed(host, allowedHosts)) {
            throw IOException("descriptor fetch host not allowed: $host")
        }
        val first = request.tag(InitialHost::class.java)?.host ?: host
        var gated = request.newBuilder()
            .tag(InitialHost::class.java, InitialHost(first))
            .build()
        if (host != first) {
            gated = gated.newBuilder().removeHeader("Cookie").build()
        }
        return chain.proceed(gated)
    }
}
