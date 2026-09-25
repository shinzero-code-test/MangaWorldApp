package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.remote.scraper.ChapterLockedException
import com.exapps.mangaworld.core.data.remote.scraper.CloudflareChallengeException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [SourceHealthPolicy] to live read traffic and isolates drifted
 * sources (§11B).
 *
 * Observed calls (repository layer, scraper result BEFORE cache fallback):
 * - `home`: anomaly when failed OR all of featured/latest/trending are empty.
 * - `detail`: anomaly when failed (an empty-but-titled detail is not drift —
 *   the title parsed, the site is up; chapters may legitimately lag).
 * - `pages`: anomaly when failed OR the page list is empty (a chapter with no
 *   renderable pages is certainly broken output).
 * - `search`/browse/genre: never observed (legitimately empty by design).
 *
 * Errors that are NOT drift never count: coroutine cancellation, Cloudflare
 * challenges (environment control, solved out-of-band), chapter locks
 * (product state with its own UI).
 *
 * Quarantine enforcement: the remote/override registration is removed so the
 * builtin resumes (overrides) or the source goes dark (new ids) — the same
 * removal primitives as the kill-switch path. Pure builtins (no index record)
 * only record the degraded signal: there is no fallback to serve, so removing
 * them would brick the source with no recovery path.
 */
@Singleton
class SourceHealthMonitor @Inject constructor(
    private val store: HealthStore,
    private val index: PluginIndexStore,
    private val registry: SourceRegistry,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) {

    /** Feed one scraper-level observation; returns the resulting state. */
    suspend fun observeHome(id: String, result: Result<*>): HealthState =
        observe(id, failedOf(result), emptyPrimary = result.map { it.isHomeEmpty() }.getOrDefault(false))

    suspend fun observeDetail(id: String, result: Result<*>): HealthState =
        observe(id, failedOf(result), emptyPrimary = false)

    suspend fun observePages(id: String, result: Result<*>): HealthState =
        observe(
            id,
            failedOf(result),
            emptyPrimary = result.map { (it as? List<*>)?.isEmpty() ?: false }.getOrDefault(false)
        )

    /**
     * Explicit re-smoke after quarantine (future UI/manual trigger): a
     * successful non-empty home read resets the counter and returns the record
     * to INSTALLED (the user re-enables from there — quarantine never
     * self-clears into serving).
     */
    suspend fun reverify(id: String): Boolean = kotlinx.coroutines.withContext(io) {
        val scraper = registry.scraperFor(id) ?: return@withContext false
        val ok = runCatching { scraper.getHomeData().getOrThrow() }
            .map { !it.isHomeEmpty() }
            .getOrDefault(false)
        if (!ok) return@withContext false
        store.save(id, SourceHealthPolicy.initial())
        index.get(id)?.let { rec ->
            if (rec.status == PluginStatus.QUARANTINED) {
                index.put(rec.copy(status = PluginStatus.INSTALLED))
            }
        }
        true
    }

    /** Current state without recording (badges, diagnostics). */
    suspend fun snapshot(id: String): HealthState = store.load(id).state

    private suspend fun observe(id: String, failed: Boolean, emptyPrimary: Boolean): HealthState =
        kotlinx.coroutines.withContext(io) {
            val current = store.load(id)
            when (val d = SourceHealthPolicy.observe(current, failed, emptyPrimary, nowMs())) {
                is SourceHealthPolicy.Decision.Reset -> {
                    if (current.anomalies != 0 || current.state != HealthState.OK) {
                        store.save(id, SourceHealthPolicy.initial())
                    }
                    HealthState.OK
                }
                is SourceHealthPolicy.Decision.Hold -> {
                    store.save(id, SourceHealthPolicy.Observation(d.anomalies, d.state, current.lastQuarantinedAt))
                    d.state
                }
                is SourceHealthPolicy.Decision.Quarantine -> {
                    store.save(
                        id,
                        SourceHealthPolicy.Observation(d.anomalies, HealthState.QUARANTINED, nowMs())
                    )
                    applyQuarantine(id)
                    HealthState.QUARANTINED
                }
            }
        }

    private suspend fun applyQuarantine(id: String) {
        val record = index.get(id)
        if (record == null) {
            // Pure builtin: no fallback exists — record only, keep serving.
            return
        }
        if (registry.isOverridden(id)) {
            registry.clearOverride(id)
        }
        registry.unregisterRemote(id)
        index.put(record.copy(status = PluginStatus.QUARANTINED))
    }

    private fun failedOf(result: Result<*>): Boolean {
        val e = result.exceptionOrNull() ?: return false
        // Environment/product states, not site drift — never counted.
        if (e is CancellationException) return false
        if (e is CloudflareChallengeException) return false
        if (e is ChapterLockedException) return false
        var cause = e.cause
        while (cause != null) {
            if (cause is CloudflareChallengeException || cause is ChapterLockedException) return false
            cause = cause.cause
        }
        return true
    }

    private fun Any?.isHomeEmpty(): Boolean {
        val home = this as? com.exapps.mangaworld.domain.model.HomeData ?: return true
        return home.featured.isEmpty() && home.latestChapters.isEmpty() && home.trending.isEmpty()
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
