package com.exapps.mangaworld.core.source.sync

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

/**
 * Phase 2B redirect-safe HTTP fetcher (plan §4 implementation rules).
 *
 * - The injected [Call.Factory] MUST have automatic redirects disabled
 *   (`followRedirects(false)` — see `PluginSyncModule`); every `Location` is
 *   resolved through [HostPolicy.resolveRedirect] and re-validated before the
 *   next hop is issued. Hop count capped, https→http downgrade rejected at any hop.
 * - Cookies are re-resolved per hop via [cookieHeader] and attached only AFTER
 *   hop validation — the previous hop's `Cookie` header is never forwarded to a
 *   new host (otherwise the allow-list cannot stop cross-host exfiltration).
 * - Initial URL must be https unless [allowInsecure] (test-only flag for
 *   scripted doubles; never true in production).
 *
 * No Android APIs (OkHttp + coroutines only) — JVM-testable. Constructed by
 * `PluginSyncModule` (the redirect-disabled client and cookie resolver are
 * assembly concerns); tests build it directly.
 */
class OkHttpPluginFetcher(
    private val callFactory: Call.Factory,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
    private val allowInsecure: Boolean = false,
    private val cookieHeader: (suspend (url: String) -> String?)? = null
) : PluginFetcher {

    override suspend fun get(
        url: String,
        allowedHosts: Set<String>,
        maxBytes: Long,
        headers: Map<String, String>
    ): PluginFetcher.FetchResult = withContext(io) {
        val first = java.net.URI(url.trim())
        if (!first.scheme.equals("https", ignoreCase = true) && !allowInsecure) {
            throw PluginFetcher.FetchFailure.Insecure(url)
        }
        var current = first.toASCIIString()
        var hops = 0
        while (true) {
            val request = buildRequest(current, headers)
            // NOTE: on release builds the Firebase Perf plugin rewrites this
            // call to FirebasePerfOkHttpClient.execute() (auto network tracing).
            // Instrumentation stays disabled for debug builds (app/build.gradle.kts)
            // so JVM unit tests reach the double directly — without that flag every
            // test here dies inside android.os.Bundle.clone ("not mocked").
            val response = runCatching {
                callFactory.newCall(request).execute()
            }.getOrElse { e ->
                throw PluginFetcher.FetchFailure.Network(e)
            }
            // Drain the decision out of the response, then act: the next hop is
            // issued only after this response is validated. Close discipline
            // matters: OkHttp's close() THROWS on responses not eligible for a
            // body (1xx/204/304), so control responses are closed quietly and
            // only body-carrying 2xx go through `use`. A 304 closed via `use`
            // would mask NotModified with IllegalStateException — i.e. every
            // ETag short-circuit would crash instead of ending the sync early.
            var redirectLocation: String? = null
            var redirectCode = 0
            var result: PluginFetcher.FetchResult? = null
            var responseEtag: String? = null
            // NOTE: okhttp3.Response has its own `isRedirect` member (any 3xx);
            // the distribution rule needs 301..308 only, so the check is spelled
            // out — a shadowed extension would route 304s into the redirect arm.
            val code = response.code
            // 304 sits inside 301..308 but is NOT a redirect — check it before
            // the redirect arm, otherwise every ETag hit becomes a rejection.
            if (code == 304) {
                runCatching { response.close() }
                throw NotModified()
            }
            if (code in 301..308) {
                redirectLocation = response.header("Location")
                redirectCode = code
                runCatching { response.close() }
            } else {
                if (!response.isSuccessful) {
                    runCatching { response.close() }
                    throw PluginFetcher.FetchFailure.Http(code, current.safeLog())
                }
                if (response.body == null) {
                    // e.g. 204: successful yet bodiless — close() would throw.
                    runCatching { response.close() }
                    throw PluginFetcher.FetchFailure.Network(
                        IllegalStateException("empty body")
                    )
                }
                responseEtag = response.header("ETag")?.takeIf { it.isNotBlank() }
                response.use { res ->
                    result = PluginFetcher.FetchResult(
                        body = readCapped(res, maxBytes),
                        finalUrl = current,
                        etag = responseEtag
                    )
                }
            }
            result?.let { return@withContext it }
            val decision = HostPolicy.resolveRedirect(
                currentUrl = current,
                location = redirectLocation,
                allowedHosts = allowedHosts,
                hopsUsed = hops
            )
            when (decision) {
                is HostPolicy.RedirectDecision.Follow -> {
                    current = decision.url
                    hops++
                    continue
                }
                is HostPolicy.RedirectDecision.NoRedirect ->
                    throw PluginFetcher.FetchFailure.RedirectRejected(
                        "empty Location (HTTP $redirectCode)"
                    )
                is HostPolicy.RedirectDecision.Reject ->
                    throw PluginFetcher.FetchFailure.RedirectRejected(decision.reason.name)
            }
        }
        // Unreachable: every non-Follow hop throws or returns above.
        throw IllegalStateException("plugin fetch escaped its redirect loop")
    }

    private suspend fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) ->
            if (!k.equals("Cookie", ignoreCase = true)) builder.header(k, v)
        }
        // Attached only after hop validation, resolved fresh for THIS url.
        cookieHeader?.invoke(url)?.takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }
        return builder.build()
    }

    private fun readCapped(response: Response, maxBytes: Long): ByteArray {
        val body = response.body ?: throw PluginFetcher.FetchFailure.Network(
            IllegalStateException("empty body")
        )
        // Content-Length pre-check avoids buffering multi-MB surprises.
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw PluginFetcher.FetchFailure.TooLarge(declared)
        }
        val bytes = body.bytes()
        if (bytes.size > maxBytes) {
            throw PluginFetcher.FetchFailure.TooLarge(bytes.size.toLong())
        }
        return bytes
    }

    /** Internal 304 signal: translated to a null body upstream, never an error. */
    class NotModified : Exception()

    private fun String.safeLog(): String = takeWhile { it != '?' }
}
