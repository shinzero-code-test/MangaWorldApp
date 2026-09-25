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
 * - Initial URL must be https unless [allowInsecure] (JVM tests against
 *   MockWebServer only; never true in production).
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
        // TEMPORARY DEBUG (revert): prove which get() body executes.
        if (System.getProperty("probe.fresh") == "1") throw IllegalArgumentException("FRESH-PROBE")
        val first = java.net.URI(url.trim())
        if (!first.scheme.equals("https", ignoreCase = true) && !allowInsecure) {
            throw PluginFetcher.FetchFailure.Insecure(url)
        }
        var current = first.toASCIIString()
        var hops = 0
        while (true) {
            // TEMPORARY DEBUG (revert): inlined buildRequest.
            val requestBuilder = Request.Builder().url(current).get()
            headers.forEach { (k, v) ->
                if (!k.equals("Cookie", ignoreCase = true)) requestBuilder.header(k, v)
            }
            cookieHeader?.invoke(current)?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("Cookie", it)
            }
            val request = requestBuilder.build()
            // TEMPORARY DEBUG (revert): stage-tag the newCall/execute boundary.
            val call = try {
                callFactory.newCall(request)
            } catch (e: Throwable) {
                throw IllegalArgumentException("STAGE-newCall", e)
            }
            val response = try {
                call.execute()
            } catch (e: AbstractMethodError) {
                throw UnsupportedOperationException("STAGE-abstract", e)
            } catch (e: NoSuchMethodError) {
                throw UnsupportedOperationException("STAGE-nosuchmethod", e)
            } catch (e: LinkageError) {
                throw UnsupportedOperationException("STAGE-linkage", e)
            } catch (e: RuntimeException) {
                throw IllegalStateException("STAGE-runtime", e)
            } catch (e: Throwable) {
                throw IllegalArgumentException("STAGE-other", e)
            }
            // Drain the decision out of the closed response, then act: the next
            // hop is issued only after this response is closed and validated.
            var redirectLocation: String? = null
            var redirectCode = 0
            var result: PluginFetcher.FetchResult? = null
            var responseEtag: String? = null
            response.use { res ->
                // NOTE: okhttp3.Response has its own `isRedirect` member (any 3xx);
                // the distribution rule needs Location-bearing 301..308 only, so the
                // check is spelled out — a shadowed extension would route 304s here.
                val location = if (res.code in 301..308) res.header("Location") else null
                if (res.code in 301..308) {
                    redirectLocation = location
                    redirectCode = res.code
                } else {
                    if (!res.isSuccessful) {
                        if (res.code == 304) throw NotModified()
                        throw PluginFetcher.FetchFailure.Http(res.code, current.safeLog())
                    }
                    responseEtag = res.header("ETag")?.takeIf { it.isNotBlank() }
                    // TEMPORARY DEBUG (revert): inlined readCapped.
                    val cappedBody = res.body
                        ?: throw PluginFetcher.FetchFailure.Http(res.code, current.safeLog())
                    val inlineBytes = cappedBody.bytes()
                    result = PluginFetcher.FetchResult(
                        body = inlineBytes,
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
