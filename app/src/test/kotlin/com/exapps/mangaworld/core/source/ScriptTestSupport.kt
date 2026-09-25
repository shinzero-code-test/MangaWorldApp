package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.source.script.ScriptBridge
import com.exapps.mangaworld.core.source.script.ScriptBridgeSession
import com.exapps.mangaworld.core.source.script.ScriptContextFactory
import com.exapps.mangaworld.core.source.script.ScriptFetcher
import com.exapps.mangaworld.core.source.script.ScriptLogger
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Shared scaffolding for the Phase 3 script-sandbox tests (bridge, containment,
 * interpreter mode, runner). Everything runs on plain JVM CI: Rhino is pure
 * Java, fetchers are fakes, dispatchers are `Unconfined`.
 */
object ScriptTestSupport {

    val sandbox = ScriptContextFactory()

    val logs = mutableListOf<Triple<String, String, String>>()
    val logger = ScriptLogger { level, tag, msg -> logs += Triple(level, tag, msg) }

    fun clearLogs() = logs.clear()

    fun manifest(
        id: String = "scriptpilot",
        engine: SourceEngine = SourceEngine.SCRIPT,
        baseUrl: String = "https://script.example",
        allowedHosts: Set<String> = setOf("script.example", "cdn.script.example"),
        timeoutMs: Int = 15_000,
        maxResponseMb: Int = 10,
        bridgeApi: Int? = 1,
        scriptSha256: String? = "0".repeat(64),
        config: Map<String, String> = emptyMap()
    ) = PluginManifest(
        id = SourceId(id),
        version = 1,
        minAppVersion = "9.0.0",
        issuedAt = "2026-09-25T00:00:00Z",
        bridgeApi = bridgeApi,
        names = mapOf("ar" to "سكربت", "en" to "Script"),
        logo = null,
        engine = engine,
        engineApi = 1,
        baseUrl = baseUrl,
        requiresVerification = false,
        enabledByDefault = false,
        requiresPermission = false,
        config = config,
        apiPaths = emptyMap(),
        allowedHosts = allowedHosts,
        timeoutMs = timeoutMs,
        maxResponseMb = maxResponseMb,
        scriptSha256 = scriptSha256,
        signatureKeyId = "test"
    )

    class FakeFetcher(
        val bodies: MutableMap<String, ByteArray> = mutableMapOf(),
        var defaultStatus: Int = 200,
        val seen: MutableList<Triple<String, Map<String, String>, Set<String>>> = mutableListOf()
    ) : ScriptFetcher {
        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
            timeoutMs: Int,
            allowedHosts: Set<String>
        ): ScriptFetcher.Response {
            seen += Triple(url, headers, allowedHosts)
            val body = bodies[url]
                ?: return ScriptFetcher.Response(defaultStatus, emptyMap(), ByteArray(0))
            if (body.size > maxBytes) throw AssertionError("test double: budget bypassed")
            return ScriptFetcher.Response(200, emptyMap(), body)
        }
    }

    fun session(
        manifest: PluginManifest = manifest(),
        fetcher: ScriptFetcher = FakeFetcher(),
        logger: ScriptLogger = this.logger
    ) = ScriptBridgeSession(manifest, fetcher, logger)

    /**
     * Evaluates [source] as STATEMENTS (any script shape, not just expressions)
     * and returns the JSON-stringified completion value, or null for
     * `undefined`/empty completion. Non-terminating scripts surface as the
     * sandbox refusal (budget/cancellation), never a hang.
     */
    fun evalJson(source: String, session: ScriptBridgeSession = session()): String? =
        sandbox.run { cx ->
            val scope = cx.initStandardObjects()
            ScriptBridge.install(cx, scope, session)
            val raw = cx.evaluateString(scope, source, "<test>", 1, null)
            if (raw == null || raw === Undefined.instance) return@run null
            ScriptableObject.putProperty(scope, "__result__", raw)
            val json = cx.evaluateString(scope, "JSON.stringify(__result__)", "<test>", 1, null)
            Context.toString(json).takeIf { it != "undefined" }
        }
}
