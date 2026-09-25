package com.exapps.mangaworld.core.source.script

import com.exapps.mangaworld.core.source.plugins.HostPolicy
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Narrow JS bridge: the ONLY globals a script may call (plan §5).
 *
 * - `fetch(url, headers?)` → body string. Entry URL https-only, host inside the
 *   manifest's effective set; redirects re-validated per hop by the fetcher.
 * - `parse(html, baseUrl?)` → document handle (an int; the DOM never crosses).
 * - `selectText(handle, selector)` / `selectAttr(handle, selector, attr)` /
 *   `selectHtml(handle, selector)` → string arrays (capped).
 * - `resolveUrl(handle, href)` → absolutized string.
 * - `log(level, msg)` → capped diagnostic line.
 *
 * Containment properties (all covered by `ScriptContainmentTest`):
 * - Boundary values are primitives/strings/arrays of strings only. Documents
 *   stay in a per-call session map; a forged handle only addresses the same
 *   call's documents (fail-closed when unknown).
 * - `Cookie` request headers are never caller-settable; cookies attach per hop
 *   from the solver store after validation.
 * - No file/prefs/reflection/timers/sockets/Android APIs exist in the scope:
 *   the installer deletes every host-object name ([ScriptContract.REMOVED_HOST_NAMES]).
 * - Every input is length/shape-validated; violations fail the call with
 *   [ScriptBridgeException], never the app.
 *
 * Blocking note: [ScriptFetcher.fetch] is suspend but Rhino calls are
 * synchronous, so fetch bridges via `runBlocking` on the script thread. The
 * per-fetch call timeout bounds how long a cancelled call can linger; the
 * instruction observer separately aborts runaway JS promptly.
 */
fun interface ScriptLogger {
    fun log(level: String, tag: String, msg: String)
}

class ScriptBridgeSession(
    val manifest: PluginManifest,
    val fetcher: ScriptFetcher,
    val logger: ScriptLogger
) {
    val tag = "script:${manifest.id.value}"
    val effectiveHosts: Set<String> = manifest.effectiveHosts
    val maxResponseBytes: Long =
        manifest.maxResponseMb.coerceIn(1, 50) * 1024L * 1024L
    val timeoutMs: Int = manifest.timeoutMs

    private val documents = HashMap<Int, org.jsoup.nodes.Document>()
    private var nextHandle = 1

    fun store(doc: org.jsoup.nodes.Document): Int {
        val h = nextHandle++
        documents[h] = doc
        return h
    }

    fun lookup(handle: Int): org.jsoup.nodes.Document =
        documents[handle] ?: throw ScriptBridgeException("unknown document handle")
}

/** Installs the seven bridge globals into a fresh scope. Call once per scope. */
object ScriptBridge {

    fun install(cx: Context, scope: Scriptable, session: ScriptBridgeSession) {
        putFn(scope, ScriptContract.FN_FETCH, FetchFn(session))
        putFn(scope, ScriptContract.FN_PARSE, ParseFn(session))
        putFn(scope, ScriptContract.FN_SELECT_TEXT, SelectTextFn(session))
        putFn(scope, ScriptContract.FN_SELECT_ATTR, SelectAttrFn(session))
        putFn(scope, ScriptContract.FN_SELECT_HTML, SelectHtmlFn(session))
        putFn(scope, ScriptContract.FN_RESOLVE_URL, ResolveUrlFn(session))
        putFn(scope, ScriptContract.FN_LOG, LogFn(session))
        // The actual containment: without these names script code has no path
        // to Java at all (verified by ScriptContainmentTest escape vectors).
        // Shadowing (not deletion): sets each host root to `undefined`, then
        // READS BACK to prove it — a silently surviving root fails closed and
        // loud instead of breaching quietly. `typeof` reports "undefined" and
        // any dereference throws a TypeError either way.
        ScriptContract.REMOVED_HOST_NAMES.forEach { name ->
            ScriptableObject.putProperty(scope, name, Undefined.instance)
            val check = scope.get(name, scope)
            if (check !== Undefined.instance && check != null && check !== Scriptable.NOT_FOUND) {
                throw ScriptException("cannot secure scope ($name)")
            }
        }
    }

    private fun putFn(scope: Scriptable, name: String, fn: BaseFunction) {
        ScriptableObject.putProperty(scope, name, fn)
    }

    // ─── Argument helpers ────────────────────────────────────────────────────

    internal fun requiredString(args: Array<out Any?>, index: Int, what: String, maxChars: Int): String {
        val raw = args.getOrNull(index)
            ?: throw ScriptBridgeException("$what is required")
        if (raw == Undefined.instance || raw == null) {
            throw ScriptBridgeException("$what is required")
        }
        val s = Context.toString(raw)
        if (s.isBlank()) throw ScriptBridgeException("$what must not be blank")
        if (s.length > maxChars) throw ScriptBridgeException("$what exceeds $maxChars chars")
        return s
    }

    internal fun requiredHandle(args: Array<out Any?>, session: ScriptBridgeSession): org.jsoup.nodes.Document {
        val raw = args.getOrNull(0)
        val handle = when (raw) {
            is Number -> raw.toInt()
            else -> throw ScriptBridgeException("document handle must be a number")
        }
        return session.lookup(handle)
    }

    internal fun headersArg(args: Array<out Any?>): Map<String, String> {
        val raw = args.getOrNull(1) ?: return emptyMap()
        if (raw == Undefined.instance || raw == null) return emptyMap()
        if (raw !is Scriptable) throw ScriptBridgeException("headers must be an object")
        val out = LinkedHashMap<String, String>()
        for (id in raw.ids) {
            if (out.size >= MAX_HEADER_ENTRIES) break
            val key = id.toString()
            if (key.equals("Cookie", ignoreCase = true)) {
                throw ScriptBridgeException("Cookie header is managed by the bridge")
            }
            if (key.length > MAX_HEADER_KEY_CHARS) throw ScriptBridgeException("header name too long")
            if (!HEADER_NAME_REGEX.matches(key)) throw ScriptBridgeException("bad header name")
            val value = Context.toString(ScriptableObject.getProperty(raw, key.toString()))
            if (value.length > MAX_HEADER_VALUE_CHARS) throw ScriptBridgeException("header value too long")
            if ('\r' in value || '\n' in value || '\r' in key || '\n' in key) {
                throw ScriptBridgeException("header CR/LF rejected")
            }
            out[key.toString()] = value
        }
        return out
    }

    internal fun capItems(items: List<String>): List<String> =
        items.take(ScriptContract.MAX_SELECT_RESULTS).map { it.take(ScriptContract.MAX_ITEM_CHARS) }

    internal fun toNativeArray(cx: Context, scope: Scriptable, items: List<String>): Scriptable {
        val arr = cx.newArray(scope, items.size)
        items.forEachIndexed { i, s -> arr.put(i, arr, s) }
        return arr
    }

    private const val MAX_HEADER_ENTRIES = 32
    private const val MAX_HEADER_KEY_CHARS = 256
    private const val MAX_HEADER_VALUE_CHARS = 4 * 1024
    private val HEADER_NAME_REGEX = Regex("^[A-Za-z0-9-]+$")

    // ─── Functions ───────────────────────────────────────────────────────────

    private class FetchFn(val session: ScriptBridgeSession) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val url = requiredString(args, 0, "url", ScriptContract.MAX_URL_CHARS)
            val headers = headersArg(args)
            val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
                ?: throw ScriptBridgeException("unparseable url")
            if (!uri.scheme.equals("https", ignoreCase = true)) {
                throw ScriptBridgeException("fetch requires https")
            }
            if (uri.userInfo != null) throw ScriptBridgeException("userinfo urls rejected")
            val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
            if (!HostPolicy.isHostAllowed(host, session.effectiveHosts)) {
                throw ScriptBridgeException("host not allowed")
            }
            val res = try {
                runBlocking {
                    session.fetcher.fetch(
                        url = url,
                        headers = headers,
                        maxBytes = session.maxResponseBytes,
                        timeoutMs = session.timeoutMs,
                        allowedHosts = session.effectiveHosts
                    )
                }
            } catch (e: ScriptException) {
                throw e
            } catch (e: Exception) {
                throw ScriptHttpException("fetch failed (${e.message?.take(120)})")
            }
            if (res.status !in 200..299) {
                throw ScriptHttpException("HTTP ${res.status}")
            }
            return res.body.toString(Charsets.UTF_8)
        }
    }

    private class ParseFn(val session: ScriptBridgeSession) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val html = requiredString(args, 0, "html", ScriptContract.MAX_STRING_ARG_CHARS)
            val baseRaw = args.getOrNull(1)?.takeUnless { it == Undefined.instance || it == null }
                ?.let { Context.toString(it).take(ScriptContract.MAX_URL_CHARS) }
                ?: session.manifest.baseUrl
            val baseUri = runCatching { java.net.URI(baseRaw.trim()) }.getOrNull()
                ?: throw ScriptBridgeException("unparseable baseUrl")
            if (!baseUri.scheme.equals("https", ignoreCase = true)) {
                throw ScriptBridgeException("baseUrl requires https")
            }
            val baseHost = baseUri.host?.lowercase()?.trimEnd('.').orEmpty()
            if (!HostPolicy.isHostAllowed(baseHost, session.effectiveHosts)) {
                throw ScriptBridgeException("baseUrl host not allowed")
            }
            val doc = org.jsoup.Jsoup.parse(html, baseRaw)
            return session.store(doc)
        }
    }

    private abstract class SelectFn(val session: ScriptBridgeSession) : BaseFunction() {
        data class Target(val doc: org.jsoup.nodes.Document, val selector: String)

        fun target(args: Array<out Any?>): Target {
            val doc = requiredHandle(args, session)
            val selector = requiredString(args, 1, "selector", ScriptContract.MAX_SELECTOR_CHARS)
            return Target(doc, selector)
        }

        fun select(t: Target): List<org.jsoup.nodes.Element> = try {
            t.doc.select(t.selector)
        } catch (e: Exception) {
            throw ScriptBridgeException("bad selector (${e.message?.take(120)})")
        }
    }

    private class SelectTextFn(session: ScriptBridgeSession) : SelectFn(session) {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val t = target(args)
            return toNativeArray(cx, scope, capItems(select(t).map { it.text() }))
        }
    }

    private class SelectAttrFn(session: ScriptBridgeSession) : SelectFn(session) {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val t = target(args)
            val attr = requiredString(args, 2, "attr", 64)
            if (!ATTR_NAME_REGEX.matches(attr)) throw ScriptBridgeException("bad attr name")
            return toNativeArray(cx, scope, capItems(select(t).map { absolutize(it, attr) }))
        }
    }

    private class SelectHtmlFn(session: ScriptBridgeSession) : SelectFn(session) {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val t = target(args)
            return toNativeArray(cx, scope, capItems(select(t).map { it.outerHtml() }))
        }
    }

    private class ResolveUrlFn(val session: ScriptBridgeSession) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val doc = requiredHandle(args, session)
            val href = requiredString(args, 1, "href", ScriptContract.MAX_URL_CHARS)
            return absolutizeHref(doc, href)
        }
    }

    private class LogFn(val session: ScriptBridgeSession) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val level = args.getOrNull(0)?.takeUnless { it == Undefined.instance || it == null }
                ?.let { Context.toString(it).lowercase().take(16) } ?: "info"
            if (level !in LOG_LEVELS) throw ScriptBridgeException("bad log level")
            val msg = args.getOrNull(1)?.takeUnless { it == Undefined.instance || it == null }
                ?.let { Context.toString(it) } ?: ""
            session.logger.log(level, session.tag, msg.take(ScriptContract.MAX_LOG_CHARS))
            return Undefined.instance
        }
    }

    // ─── Shared URL helpers (object-level: no companion inside an object) ────

    private val ATTR_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_:.-]{0,63}$")
    private val LOG_LEVELS = setOf("debug", "info", "warn", "error")

    private val URL_ATTRS = setOf("href", "src", "data-src", "data-lazy-src", "action")

    internal fun absolutize(el: org.jsoup.nodes.Element, attr: String): String {
        val raw = el.attr(attr)
        if (raw.isBlank()) return ""
        if (attr.lowercase() !in URL_ATTRS) return raw
        return absolutizeHref(el.ownerDocument() ?: return raw, raw)
    }

    internal fun absolutizeHref(doc: org.jsoup.nodes.Document, href: String): String {
        val t = href.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        // Non-navigable schemes pass through untouched (engine validation
        // downstream only accepts https for page/chapter/cover URLs).
        if (lower.startsWith("data:") || lower.startsWith("javascript:") ||
            lower.startsWith("mailto:") || lower.startsWith("blob:")
        ) {
            return t
        }
        return runCatching {
            val base = doc.baseUri().takeIf { it.isNotBlank() }
                ?: return t
            java.net.URI(base).resolve(t).toASCIIString()
        }.getOrDefault(t)
    }
}
