package com.exapps.mangaworld.core.source.script

import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.Call
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw HTTP for script entry points. Policy lives in [ScriptBridge] (host
 * allow-list, https-only); this port only moves bytes with redirect-following
 * disabled so every hop can be validated before it is issued.
 *
 * Tests fake this; production uses [OkHttpScriptFetcher]. No Android APIs.
 */
interface ScriptFetcher {

    data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return status == other.status && headers == other.headers && body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
        timeoutMs: Int,
        /**
         * Manifest effective hosts for THIS call. Passed per call (not bound to
         * the fetcher) so one shared client serves many runners safely.
         */
        allowedHosts: Set<String>
    ): Response
}

/**
 * Cookie port for script traffic. Re-resolved per hop AFTER hop validation —
 * the previous hop's `Cookie` header is never forwarded to a new host
 * (plan §4: validation runs before credentials attach).
 */
fun interface ScriptCookieJar {
    suspend fun cookiesFor(url: String): String?
}

/** Production cookies: the WebView-solver store behind the normal resolvers. */
@Singleton
class SettingsScriptCookieJar @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ScriptCookieJar {
    override suspend fun cookiesFor(url: String): String? = resolveCookieForUrl(settingsRepo, url)
}

/**
 * Production [ScriptFetcher] over a redirect-disabled client (see [ScriptModule];
 * the same builder policy as distribution: no automatic redirects, per-hop
 * validation via [HostPolicy.resolveRedirect], https enforced at every hop).
 *
 * A per-fetch `callTimeout` bounds the worst case a cancelled call can linger on
 * a script thread (structured cancellation cannot preempt a socket read).
 */
@Singleton
class OkHttpScriptFetcher @Inject constructor(
    @com.exapps.mangaworld.core.source.sync.PluginSyncClient private val client: okhttp3.OkHttpClient,
    private val cookieJar: ScriptCookieJar,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) : ScriptFetcher {

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
        timeoutMs: Int,
        allowedHosts: Set<String>
    ): ScriptFetcher.Response = kotlinx.coroutines.withContext(io) {
        val first = runCatching { java.net.URI(url.trim()) }.getOrNull()
            ?: throw ScriptBridgeException("bad url")
        if (!first.scheme.equals("https", ignoreCase = true)) {
            throw ScriptBridgeException("fetch requires https")
        }
        // Per-fetch client: shares the pool/dispatcher, adds a call timeout so a
        // cancelled script call cannot pin a script thread past its quota.
        val scoped = client.newBuilder()
            .callTimeout(timeoutMs.coerceIn(1_000, 120_000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        var current = first.toASCIIString()
        var hops = 0
        // Hosts are enforced by the bridge before this port is reached; the
        // effective set is re-derived per hop from the validated chain, and the
        // bridge re-checks membership on the final URL. Defense in depth.
        while (true) {
            val builder = okhttp3.Request.Builder().url(current).get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            headers.forEach { (k, v) ->
                if (!k.equals("Cookie", ignoreCase = true)) builder.header(k, v)
            }
            cookieJar.cookiesFor(current)?.takeIf { it.isNotBlank() }?.let {
                builder.header("Cookie", it)
            }
            val request = builder.build()
            val call: Call = scoped.newCall(request)
            val raw = runCatching { call.execute() }.getOrElse { e ->
                throw ScriptHttpException("fetch failed (${e.message?.take(120)})")
            }
            var next: String? = null
            var response: ScriptFetcher.Response? = null
            // Bodiless control responses must not be closed (OkHttp throws) —
            // same discipline as distribution; only the terminal body is used.
            val code = raw.code
            if (code in 301..308 && code != 304) {
                next = raw.header("Location")
                runCatching { raw.close() }
            } else {
                val body = raw.body
                if (body == null) {
                    runCatching { raw.close() }
                    throw ScriptHttpException("empty body (HTTP $code)")
                }
                val declared = body.contentLength()
                if (declared > maxBytes) {
                    runCatching { raw.close() }
                    throw ScriptHttpException("response exceeds budget")
                }
                val bytes = runCatching { body.bytes() }.getOrElse { e ->
                    runCatching { raw.close() }
                    throw ScriptHttpException("read failed (${e.message?.take(120)})")
                }
                runCatching { raw.close() }
                if (bytes.size > maxBytes) throw ScriptHttpException("response exceeds budget")
                response = ScriptFetcher.Response(
                    status = code,
                    headers = raw.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    body = bytes
                )
            }
            if (response != null) return@withContext response
            // Redirect hop: validated against the manifest set before the next
            // request is issued — the bridge pre-checks the entry URL, this is
            // the per-hop gate (cookies attach only after it passes).
            val decision = HostPolicy.resolveRedirect(
                currentUrl = current,
                location = next,
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
                    throw ScriptHttpException("redirect without location (HTTP $code)")
                is HostPolicy.RedirectDecision.Reject ->
                    throw ScriptHttpException("redirect refused (${decision.reason.name})")
            }
        }
        // Unreachable: every non-Follow hop throws or returns above.
        throw IllegalStateException("script fetch escaped its redirect loop")
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }
}
