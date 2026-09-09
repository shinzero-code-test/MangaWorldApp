package com.exapps.mangaworld.core.firebase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The single chapter-update sweep (#9): checks favorited manga for new
 * chapters every 6h in every notification mode. Delegates to
 * [ChapterUpdateCheckerCore]; owned by [FavoriteDigestScheduler], which
 * cancels the legacy 12h checker on upgrade.
 */
@HiltWorker
class FavoriteDigestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val chapterUpdateCheckerCore: ChapterUpdateCheckerCore
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            chapterUpdateCheckerCore.checkForNewChapters()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
