package com.exapps.mangaworld.core.source.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.ContextFactory

/**
 * The single Rhino entry point app-wide (plan §5).
 *
 * - Every script [Context] is created here with `optimizationLevel = -1` (pure
 *   interpreter). Rhino's bytecode-generation mode would define and load JVM
 *   classes at runtime — the same "dynamic code loading" shape this plan rejects
 *   for APK extensions — so interpreter mode is structural, not a flag callers
 *   can forget. [RhinoInterpreterModeTest] executes through this factory and
 *   asserts the level on every run.
 * - Instruction observation is always on: [ScriptContract.INSTRUCTION_BUDGET]
 *   plus coroutine cancellation are checked every
 *   [ScriptContract.INSTRUCTION_OBSERVATION_THRESHOLD] instructions, so a
 *   CPU-bound busy loop trips fast and can never ANR the UI (execution additionally
 *   lives on the dedicated script dispatcher, never the main thread).
 * - Language level is pinned to ES6 so script authors get a stable target.
 *
 * Threading: the factory is shared and thread-safe; [Context]s are entered per
 * call and exited in `finally`. Per-call quota state rides [ThreadLocal]s that
 * [run] always clears, so pooled threads cannot leak budgets between calls.
 */
class ScriptContextFactory : ContextFactory() {

    private val remainingBudget = ThreadLocal.withInitial { Int.MAX_VALUE }
    private val cancelCheck = ThreadLocal.withInitial { { false } }

    /** Last level applied in [onContextCreated]; test-visible proof of the lock. */
    @Volatile
    internal var lastOptimizationLevel: Int = Int.MIN_VALUE
        private set

    override fun onContextCreated(cx: Context) {
        super.onContextCreated(cx)
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        // NOTE: the field-style property does NOT resolve here (Rhino keeps the
        // threshold behind the explicit setter name) — call it directly.
        cx.setInstructionObserverThreshold(ScriptContract.INSTRUCTION_OBSERVATION_THRESHOLD)
        cx.maximumInterpreterStackDepth = ScriptContract.MAX_INTERPRETER_STACK_DEPTH
        lastOptimizationLevel = cx.optimizationLevel
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        if (cancelCheck.get().invoke()) throw ScriptCancelledException()
        val left = remainingBudget.get() - ScriptContract.INSTRUCTION_OBSERVATION_THRESHOLD
        remainingBudget.set(left)
        if (left <= 0) {
            throw ScriptQuotaExceededException(
                "instruction budget exceeded (${ScriptContract.INSTRUCTION_BUDGET})"
            )
        }
    }

    /**
     * Enters a context, installs the per-call budget + cancellation probe, runs
     * [block], and always clears thread-local state.
     */
    fun <R> run(
        budget: Int = ScriptContract.INSTRUCTION_BUDGET,
        isCancelled: () -> Boolean = { false },
        block: (Context) -> R
    ): R {
        remainingBudget.set(budget)
        cancelCheck.set(isCancelled)
        try {
            val action = ContextAction<Any?> { cx -> block(cx) as Any? }
            @Suppress("UNCHECKED_CAST")
            return call(action) as R
        } finally {
            remainingBudget.remove()
            cancelCheck.remove()
        }
    }
}
