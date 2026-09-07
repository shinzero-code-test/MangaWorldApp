package com.exapps.mangaworld.core.firebase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class FirebaseSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: FirebaseSyncManager
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        syncManager.pushLocalSnapshot()
        Result.success()
    }.getOrElse { e ->
        // Permanent failures must not retry forever on the 30-min backoff.
        if (e is CancellationException) throw e
        if (e is FirebaseFirestoreException && e.code in setOf(
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                FirebaseFirestoreException.Code.INVALID_ARGUMENT
            )
        ) {
            Result.failure()
        } else {
            Result.retry()
        }
    }
}
