package com.exapps.mangaworld.core.data.download

import android.content.Context
import android.net.ConnectivityManager
import com.exapps.mangaworld.R
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.MangaRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import java.util.UUID

@HiltWorker
class AutoDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val favoriteDao: FavoriteDao,
    private val readChapterDao: ReadChapterDao,
    private val downloadQueueManager: DownloadQueueManager,
    private val mangaRepository: MangaRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.getAppSettings().first()
        if (!settings.autoDownloadNewChapters) return Result.success()
        if (settings.downloadOnWifiOnly && isNetworkMetered()) return Result.success()

        val favorites = favoriteDao.getFavoritesList()

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
                            taskId = "auto_${UUID.randomUUID()}",
                            mangaId = favorite.mangaId,
                            mangaTitle = favorite.title,
                            chapterUrl = chapter.url,
                            chapterTitle = chapter.title
                                ?: applicationContext.getString(R.string.fmt_059, chapter.displayNumber),
                            pages = pages,
                            wifiOnly = settings.downloadOnWifiOnly,
                            referer = chapter.url,
                            sourceId = source.id,
                            mangaSlug = favorite.slug
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Skip this manga on error
                continue
            }
        }

        return Result.success()
    }

    private fun isNetworkMetered(): Boolean =
        applicationContext.getSystemService(ConnectivityManager::class.java)
            ?.isActiveNetworkMetered
            ?: true

    companion object {
        private const val WORK_NAME = "auto_download_periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
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
