package com.exapps.mangaworld.core.source.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Phase 2B distribution worker: runs [PluginSyncEngine] off the main thread.
 * Transport failures retry with backoff; deterministic rejections (tampered
 * index/payloads, incompatibility) fail without retry — the next periodic run
 * picks them up again in 24h.
 */
@HiltWorker
class PluginSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: PluginSyncEngine,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val scheduler: PluginSyncScheduler
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = engine.sync(
                trustedKeys = remoteConfigManager.pluginTrustedKeys(),
                host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                killSwitchJson = remoteConfigManager.pluginKillSwitchJson(),
                baseDir = File(applicationContext.filesDir, "plugins")
            )
            android.util.Log.i(
                "PluginSync",
                "sync done: ${result.outcomes.size} entries, " +
                    "revoked=${result.revocationsApplied}, notModified=${result.indexNotModified}"
            )
            // v9.1.1: a finished sweep (even all-rejected) counts as "checked"
            // for bootstrap staleness — only transport failure retries early.
            scheduler.recordSyncCompleted()
            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: PluginFetcher.FetchFailure.Network) {
            Result.retry()
        } catch (_: Exception) {
            // Deterministic rejection (tampered index, bad schema): retrying
            // immediately changes nothing; the periodic schedule re-checks.
            // Still counts as checked so boot-stale triggers don't loop it.
            scheduler.recordSyncCompleted()
            Result.failure()
        }
    }
}
