package com.exapps.mangaworld.core.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class AutoDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val favoriteDao: FavoriteDao,
    private val readChapterDao: ReadChapterDao,
    private val cacheDao: MangaCacheDao,
    private val downloadQueueManager: DownloadQueueManager,
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.getAppSettings().first()
        if (!settings.autoDownloadNewChapters) return Result.success()
        if (settings.downloadOnWifiOnly && !isNetworkMetered().not()) return Result.success()

        val favorites = favoriteDao.getFavoritesList()
        val chaptersDownloaded = mutableListOf<String>()

        for (favorite in favorites) {
            try {
                val source = MangaSource.fromId(favorite.sourceId)
                val detail = mangaRepository.getMangaDetail(favorite.slug, source).getOrNull() ?: continue
                val readChapters = readChapterDao.getReadChapters(favorite.mangaId).first().toSet()

                // Sort chapters by number descending, find the highest read chapter
                val sortedChapters = detail.chapters.sortedByDescending { it.number }
                val lastReadChapterNumber = sortedChapters
                    .firstOrNull { it.number in readChapters }?.number ?: 0f

                // Get next 3 unread chapters AFTER the last read one
                val unreadChapters = sortedChapters
                    .filter { it.number > lastReadChapterNumber && it.number !in readChapters }
                    .sortedBy { it.number }
                    .take(3)

                for (chapter in unreadChapters) {
                    val alreadyDownloaded = downloadQueueManager.isChapterDownloaded(
                        favorite.mangaId, chapter.url
                    )
                    if (alreadyDownloaded) continue

                    val pages = mangaRepository.getChapterPages(
                        favorite.slug, chapter.url, source
                    ).getOrNull() ?: continue

                    if (pages.isNotEmpty()) {
                        downloadQueueManager.enqueueAndRun(
                            taskId = "auto_${System.currentTimeMillis()}_${chapter.number}",
                            mangaId = favorite.mangaId,
                            mangaTitle = favorite.title,
                            chapterUrl = chapter.url,
                            chapterTitle = chapter.title ?: "الفصل ${chapter.displayNumber}",
                            pages = pages,
                            wifiOnly = true,
                            referer = chapter.url
                        )
                        chaptersDownloaded.add("${favorite.title} - ${chapter.displayNumber}")
                    }
                }
            } catch (e: Exception) {
                // Skip this manga on error
                continue
            }
        }

        return Result.success()
    }

    private suspend fun isNetworkMetered(): Boolean {
        // Simplified check - actual implementation would use ConnectivityManager
        return false
    }

    companion object {
        private const val WORK_NAME = "auto_download_periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
