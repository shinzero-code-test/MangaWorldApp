package com.exapps.mangaworld.core.source.script

/**
 * Phase 3 script-plugin contract (bridge API v1).
 *
 * A script plugin is a signed `plugin.json` (`engine: "script"`) plus a `source.js`
 * executed in a Rhino sandbox. The ONLY globals a script may touch are the bridge
 * functions installed per call by [ScriptBridge]; standard JS built-ins (Object,
 * Array, String, JSON, …) stay, but every Java-host escape hatch (`Packages`,
 * `java`, `org`, `JavaAdapter`, `JavaImporter`) is deleted from the scope before
 * the script runs. Values crossing the boundary are primitives/strings/arrays of
 * those — a Java object (notably any DOM node) can never reach script code, and
 * any `NativeJavaObject` leaking back is a hard failure, never silent coercion.
 *
 * Pure JVM-safe (no Android imports) so the whole sandbox executes on plain JVM CI.
 */
object ScriptContract {

    /** Current bridge level. Manifests declaring any other `bridgeApi` fail closed. */
    const val BRIDGE_API_V1 = 1
    // ─── Entry points (fixed; the runner calls these by name) ────────────────

    const val ENTRY_HOME = "home"
    const val ENTRY_DETAIL = "detail"
    const val ENTRY_PAGES = "pages"
    const val ENTRY_SEARCH = "search"
    const val ENTRY_BROWSE = "browse"

    /**
     * Optional extension (not in the plan's required set): `genres(ctx)` →
     * string[]. Absent → `getGenres()` succeeds with an empty list. Optional so
     * old scripts keep working when the UI gains new surfaces.
     */
    const val ENTRY_GENRES = "genres"

    // ─── Quotas (fail the single call, never the app) ────────────────────────

    /** Independent byte cap for `source.js` itself (NOT `maxResponseMb`). */
    const val SCRIPT_MAX_BYTES = 512 * 1024

    /**
     * Rhino instruction budget per call. A theme-shaped parse (fetch + select a
     * few dozen nodes) executes in the low tens of thousands; a busy loop trips
     * this within milliseconds. Calibrated generously to avoid false positives
     * on slow CI runners — wall-clock timeout remains the backstop.
     */
    const val INSTRUCTION_BUDGET = 1_000_000

    /** Observer granularity: the budget/cancellation check runs this often. */
    const val INSTRUCTION_OBSERVATION_THRESHOLD = 10_000

    /** Interpreter stack bound: deep recursion fails as a script error, not a crash. */
    const val MAX_INTERPRETER_STACK_DEPTH = 10_000

    /** Dedicated pool size: script work never borrows the shared IO pool. */
    const val SCRIPT_THREADS = 2

    // ─── Bridge input/output bounds ──────────────────────────────────────────

    /** Largest single string argument accepted (HTML docs, selectors stay far below). */
    const val MAX_STRING_ARG_CHARS = 4_000_000

    const val MAX_SELECTOR_CHARS = 500
    const val MAX_URL_CHARS = 2_048
    const val MAX_LOG_CHARS = 2_000

    /** Per-call result cap: a page never legitimately yields more nodes. */
    const val MAX_SELECT_RESULTS = 1_000

    /** Longest single returned item (outerHTML of a pathological node). */
    const val MAX_ITEM_CHARS = 64 * 1024

    // ─── Bridge globals installed per call ───────────────────────────────────

    const val FN_FETCH = "fetch"
    const val FN_PARSE = "parse"
    const val FN_SELECT_TEXT = "selectText"
    const val FN_SELECT_ATTR = "selectAttr"
    const val FN_SELECT_HTML = "selectHtml"
    const val FN_RESOLVE_URL = "resolveUrl"
    const val FN_LOG = "log"

    /** Host-object names deleted from every scope before the script runs. */
    val REMOVED_HOST_NAMES = listOf(
        "Packages", "java", "javax", "org", "com", "edu", "net",
        "JavaAdapter", "JavaImporter", "JavaArray"
    )

    // ─── Per-call quotas (fail the single call, never the app) ───────────────

    /**
     * Parsed documents retained per call. A script parsing in a loop cannot
     * accumulate Jsoup DOMs past this (RAM bound the instruction budget cannot
     * see — node counting is not instruction counting).
     */
    const val MAX_DOCS_PER_CALL = 100

    /** Bridge `fetch` calls per script call (pagination stays far below this). */
    const val MAX_FETCHES_PER_CALL = 25

    /** Cumulative fetched bytes per script call across all fetches. */
    const val MAX_CALL_BYTES = 100L * 1024 * 1024

    /** Total converted result nodes per call (breadth the depth cap misses). */
    const val MAX_RESULT_NODES = 200_000
}

/** Base for every script failure: per-call quarantine, full message, no app crash. */
open class ScriptException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Wall-clock or instruction budget exhausted. */
class ScriptQuotaExceededException(message: String) : ScriptException(message)

/**
 * The awaiting coroutine was cancelled (timeout): abort promptly.
 * Extends [CancellationException] (not [ScriptException]) so aborts cooperate
 * with structured concurrency instead of being swallowed into results.
 */
internal class ScriptCancelledException : CancellationException("cancelled")

/** Bridge misuse or policy refusal (bad args, off-allowlist host, oversize). */
class ScriptBridgeException(message: String) : ScriptException(message)

/** Script-side HTTP failure (non-2xx, transport error, redirect escape). */
class ScriptHttpException(message: String) : ScriptException(message)

/** Entry-point result failed engine-owned validation (malformed models downstream). */
class ScriptResultException(message: String) : ScriptException(message)

/** `source.js` failed to compile: fail the install/call before any execution. */
class ScriptCompileException(message: String) : ScriptException(message)
