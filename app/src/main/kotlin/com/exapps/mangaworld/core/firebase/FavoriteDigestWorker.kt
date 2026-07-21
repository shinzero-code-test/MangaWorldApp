package com.exapps.mangaworld.core.firebase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic worker that checks for new chapters on favorited manga.
 * Uses [ChapterUpdateChecker] for the actual detection logic.
 * Runs every 6 hours to catch updates even when app is in background.
 */
@HiltWorker
class FavoriteDigestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val chapterUpdateChecker: ChapterUpdateChecker
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            chapterUpdateChecker.checkForUpdates(forceCheck = true)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
