package com.exapps.mangaworld.core.firebase.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-18e: the durable outbox of community writes.
 *
 * A comment/review write is persisted HERE before the first network attempt,
 * so a process death (the user swiping the app away mid-write) cannot swallow
 * the write. The worker drains whatever survived.
 *
 * Why SharedPreferences and not Room: the outbox holds a handful of small
 * rows, must be readable before the database is opened (the worker can run at
 * any time), and must not add a schema version. The same pattern the
 * notification centre already uses.
 */
interface CommunityWriteOutbox {
    /** Durably records the intent to write (idempotent per id). */
    suspend fun enqueue(write: PendingCommunityWrite)

    /** Drops a write: it landed, or it must never be retried. */
    suspend fun remove(id: String)

    /** The writes still awaiting delivery, oldest first. */
    suspend fun pending(): List<PendingCommunityWrite>

    /**
     * Records a retryable failure and returns the new attempt count. The row
     * STAYS in the outbox — the worker re-drives it with backoff.
     */
    suspend fun recordRetry(id: String, error: String): Int

    /**
     * Records a TERMINAL failure: the write can never succeed as-is, so it is
     * dropped from the queue — but it is recorded, never silently swallowed.
     */
    suspend fun recordFailure(id: String, error: String)

    /** The recorded terminal failures, newest first (diagnostics + future UI). */
    suspend fun failures(): List<CommunityWriteFailure>
}

/**
 * Production [CommunityWriteOutbox]: a SharedPreferences-backed JSON list.
 *
 * Every mutation is serialized by a mutex and committed synchronously — an
 * `apply()` would be lost if the process died right after the write, which is
 * the exact case this class exists for.
 */
@Singleton
class SharedPreferencesCommunityWriteOutbox @Inject constructor(
    @ApplicationContext context: Context
) : CommunityWriteOutbox {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun enqueue(write: PendingCommunityWrite) = mutate { writes ->
        if (writes.any { it.id == write.id }) {
            writes
        } else {
            // Bounded: a pathological loop must not grow the blob without limit.
            (writes + write).takeLast(MAX_PENDING)
        }
    }

    override suspend fun remove(id: String) = mutate { writes ->
        writes.filterNot { it.id == id }
    }

    override suspend fun pending(): List<PendingCommunityWrite> = read { writes ->
        writes.sortedBy { it.createdAt }
    }

    override suspend fun recordRetry(id: String, error: String): Int {
        var attempts = 0
        mutate { writes ->
            writes.map { write ->
                if (write.id == id) {
                    attempts = write.attempts + 1
                    write.copy(attempts = attempts, lastError = error)
                } else {
                    write
                }
            }
        }
        return attempts
    }

    override suspend fun recordFailure(id: String, error: String) {
        val write = read { writes -> writes }.firstOrNull { it.id == id } ?: return
        mutateFailures { failures ->
            (failures + CommunityWriteFailure(id = id, kind = write.kind, error = error, at = System.currentTimeMillis()))
                .takeLast(MAX_FAILURES)
        }
        remove(id)
    }

    override suspend fun failures(): List<CommunityWriteFailure> = readFailures { failures ->
        failures.sortedByDescending { it.at }
    }

    private suspend fun mutate(transform: (List<PendingCommunityWrite>) -> List<PendingCommunityWrite>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = decodeCommunityWrites(prefs.getString(KEY_PENDING, "") ?: "")
                val next = transform(current)
                // commit() (not apply()): the write must survive a process death.
                prefs.edit().putString(KEY_PENDING, encodeCommunityWrites(next)).commit()
            }
        }
    }

    private suspend fun read(
        transform: (List<PendingCommunityWrite>) -> List<PendingCommunityWrite>
    ): List<PendingCommunityWrite> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                transform(decodeCommunityWrites(prefs.getString(KEY_PENDING, "") ?: ""))
            }
        }
    }

    private suspend fun mutateFailures(
        transform: (List<CommunityWriteFailure>) -> List<CommunityWriteFailure>
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = decodeCommunityFailures(prefs.getString(KEY_FAILURES, "") ?: "")
                prefs.edit().putString(KEY_FAILURES, encodeCommunityFailures(transform(current))).commit()
            }
        }
    }

    private suspend fun readFailures(
        transform: (List<CommunityWriteFailure>) -> List<CommunityWriteFailure>
    ): List<CommunityWriteFailure> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                transform(decodeCommunityFailures(prefs.getString(KEY_FAILURES, "") ?: ""))
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "community_write_outbox"
        const val KEY_PENDING = "pending"
        const val KEY_FAILURES = "failures"

        /** Bounded queue: the foreground attempt still runs for anything dropped. */
        const val MAX_PENDING = 50
        const val MAX_FAILURES = 20
    }
}

// ─── Failure codec (pure, unit-testable) ───────────────────────────────────

internal fun CommunityWriteFailure.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("kind", kind.name)
    put("error", error)
    put("at", at)
}

internal fun encodeCommunityFailures(failures: List<CommunityWriteFailure>): String {
    val array = JSONArray()
    failures.forEach { array.put(it.toJson()) }
    return array.toString()
}

internal fun decodeCommunityFailures(raw: String): List<CommunityWriteFailure> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            val kind = runCatching { CommunityWriteKind.valueOf(obj.optString("kind", "")) }.getOrNull()
                ?: return@mapNotNull null
            CommunityWriteFailure(
                id = id,
                kind = kind,
                error = obj.optString("error", ""),
                at = obj.optLong("at", 0L)
            )
        }
    }.getOrDefault(emptyList())
}
