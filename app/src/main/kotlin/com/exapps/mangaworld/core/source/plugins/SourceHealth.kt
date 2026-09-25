package com.exapps.mangaworld.core.source.plugins

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-activation source-health monitoring (plan §11B).
 *
 * Install-time fixture smoke proves a plugin works the day it activates — it
 * says nothing about the target site redesigning its DOM three weeks later,
 * the most common real-world scraper failure mode. This layer watches LIVE
 * `home`/`detail`/`pages` results per source:
 *
 * - An anomaly = an error (except cancellations, Cloudflare challenges, and
 *   chapter locks — environment/product states, not drift) or a success with
 *   an empty primary payload (home with all lists empty, a chapter with no
 *   pages). `search` results and pagination empties NEVER count (legitimately
 *   empty by design — counting them would grey out healthy quiet sources).
 * - A per-source consecutive-anomaly counter (persisted) increments per anomaly
 *   and resets on any successful non-empty parse.
 * - Staged thresholds with hysteresis: [WARN_AT] consecutive anomalies →
 *   [HealthState.DEGRADED] ("may be broken" signal); [QUARANTINE_AT] →
 *   [HealthState.QUARANTINED] with automatic isolation (remote/override
 *   payloads unregistered so the builtin resumes; pure builtins only record —
 *   there is no fallback to serve). Re-enable requires a successful re-smoke
 *   ([SourceHealthMonitor.reverify]), never mere quiet, so flapping sources
 *   cannot oscillate. A cooldown suppresses quarantine churn right after one.
 *
 * The heuristic lives in [SourceHealthPolicy] (pure, unit-tested in isolation
 * from any source's fixtures); [HealthStore] persists counters; production
 * uses [PrefsHealthStore], tests a fake.
 */
enum class HealthState { OK, DEGRADED, QUARANTINED }

/** Which read path produced the observation. Search is never observed. */
enum class HealthKind { HOME, DETAIL, PAGES }

object SourceHealthPolicy {
    const val WARN_AT = 5
    const val QUARANTINE_AT = 10

    /** Minimum time between automatic quarantines of the same source. */
    const val QUARANTINE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    data class Observation(
        val anomalies: Int,
        val state: HealthState,
        val lastQuarantinedAt: Long
    )

    sealed interface Decision {
        data class Hold(val anomalies: Int, val state: HealthState) : Decision
        data class Quarantine(val anomalies: Int) : Decision
        data object Reset : Decision
    }

    fun initial(): Observation = Observation(0, HealthState.OK, 0L)

    /**
     * @param failed true on error (already filtered: cancellations, CF
     *   challenges and chapter locks never arrive here).
     * @param emptyPrimary true on success with an empty primary payload.
     */
    fun observe(
        current: Observation,
        failed: Boolean,
        emptyPrimary: Boolean,
        nowMs: Long
    ): Decision {
        if (current.state == HealthState.QUARANTINED) {
            // Quarantine lifts only via explicit re-smoke, never via traffic.
            return Decision.Hold(current.anomalies, HealthState.QUARANTINED)
        }
        if (!failed && !emptyPrimary) return Decision.Reset        val anomalies = current.anomalies + 1
        if (anomalies >= QUARANTINE_AT &&
            (current.lastQuarantinedAt == 0L ||
                nowMs - current.lastQuarantinedAt >= QUARANTINE_COOLDOWN_MS)
        ) {
            return Decision.Quarantine(anomalies)
        }
        val state = if (anomalies >= WARN_AT) HealthState.DEGRADED else HealthState.OK
        return Decision.Hold(anomalies, state)
    }
}

/** Narrow persistence port for health counters (SharedPreferences in prod). */
interface HealthStore {
    suspend fun load(id: String): SourceHealthPolicy.Observation
    suspend fun save(id: String, observation: SourceHealthPolicy.Observation)

    /**
     * Atomic read-modify-write (F-review: separate load/save races lost
     * increments under concurrent reads). Skips the write when [f] returns its
     * input — clean-traffic reads must not churn storage on every home load.
     */
    suspend fun <R> update(
        id: String,
        f: (SourceHealthPolicy.Observation) -> Pair<SourceHealthPolicy.Observation, R>
    ): R {
        val current = load(id)
        val (next, result) = f(current)
        if (next != current) save(id, next)
        return result
    }
}

/**
 * SharedPreferences-backed counters, mutex-serialized like the notification
 * store (raw read-modify-write races lost updates there — same trap here).
 */
@Singleton
class PrefsHealthStore @Inject constructor(
    @ApplicationContext private val context: Context
) : HealthStore {

    private val mutex = Mutex()

    override suspend fun load(id: String): SourceHealthPolicy.Observation = mutex.withLock {
        loadLocked(id)
    }

    override suspend fun save(id: String, observation: SourceHealthPolicy.Observation) {
        mutex.withLock {
            saveLocked(id, observation)
        }
    }

    /** Single-mutex read-modify-write: concurrent readers cannot interleave. */
    override suspend fun <R> update(
        id: String,
        f: (SourceHealthPolicy.Observation) -> Pair<SourceHealthPolicy.Observation, R>
    ): R = mutex.withLock {
        val current = loadLocked(id)
        val (next, result) = f(current)
        if (next != current) saveLocked(id, next)
        result
    }

    private fun loadLocked(id: String): SourceHealthPolicy.Observation {
        val raw = prefs().getString(key(id), null) ?: return SourceHealthPolicy.initial()
        runCatching {
            val o = JSONObject(raw)
            SourceHealthPolicy.Observation(
                anomalies = o.optInt("a", 0).coerceIn(0, 1_000_000),
                state = runCatching { HealthState.valueOf(o.optString("s", "OK")) }
                    .getOrDefault(HealthState.OK),
                lastQuarantinedAt = o.optLong("q", 0L).coerceAtLeast(0L)
            )
        }.getOrDefault(SourceHealthPolicy.initial())
    }

    private fun saveLocked(id: String, observation: SourceHealthPolicy.Observation) {
        val o = JSONObject()
            .put("a", observation.anomalies)
            .put("s", observation.state.name)
            .put("q", observation.lastQuarantinedAt)
        prefs().edit().putString(key(id), o.toString()).apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun key(id: String) = "health_" + id.take(80).map { c ->
        if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
    }.joinToString("")

    companion object {
        const val PREFS_FILE = "source_health"
    }
}
