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
        // v9.1.3: worker lifecycle is logcat-visible (start/finish/cause).
        // Engine outcomes already log per-entry; these lines bracket the run so
        // a silent worker (never started vs. aborted pre-log) is distinguishable.
        android.util.Log.i("PluginSync", "sync worker started")
        return try {
            engine.sync(
                trustedKeys = remoteConfigManager.pluginTrustedKeys(),
                host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                killSwitchJson = remoteConfigManager.pluginKillSwitchJson(),
                baseDir = File(applicationContext.filesDir, "plugins")
            )
            // Outcome lines are logged inside the engine (single-sourced).
            // v9.1.1: a finished sweep (even all-rejected) counts as "checked"
            // for bootstrap staleness — only transport failure retries early.
            scheduler.recordSyncCompleted()
            android.util.Log.i("PluginSync", "sync worker finished: success")
            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            android.util.Log.i("PluginSync", "sync worker cancelled")
            throw ce
        } catch (e: PluginFetcher.FetchFailure.Network) {
            android.util.Log.w("PluginSync", "sync worker transport failure, retrying: ${e.message?.take(160)}")
            Result.retry()
        } catch (e: Exception) {
            // Deterministic rejection (tampered index, bad schema): retrying
            // immediately changes nothing; the periodic schedule re-checks.
            // Still counts as checked so boot-stale triggers don't loop it.
            android.util.Log.w(
                "PluginSync",
                "sync worker failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
            )
            scheduler.recordSyncCompleted()
            Result.failure()
        }
    }
}
