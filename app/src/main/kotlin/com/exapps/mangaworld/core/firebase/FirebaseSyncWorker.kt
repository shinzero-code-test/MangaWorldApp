package com.exapps.mangaworld.core.firebase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FirebaseSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: FirebaseSyncManager
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        syncManager.pushLocalSnapshot()
        Result.success()
    }.getOrElse { Result.retry() }
}
