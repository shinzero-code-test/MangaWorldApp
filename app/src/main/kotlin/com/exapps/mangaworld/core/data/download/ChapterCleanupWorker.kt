package com.exapps.mangaworld.core.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ChapterCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueManager: DownloadQueueManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mangaId = inputData.getString(KEY_MANGA_ID) ?: return Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR) ?: return Result.failure()
        return runCatching {
            downloadQueueManager.deleteDownloadedChapterDir(mangaId, targetDir)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val KEY_MANGA_ID = "cleanup_manga_id"
        const val KEY_TARGET_DIR = "cleanup_target_dir"
    }
}
