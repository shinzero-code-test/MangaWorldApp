package com.exapps.mangaworld.core.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exapps.mangaworld.core.data.WidgetDataRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RemoteWidgetRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val widgetDataRepository: WidgetDataRepository,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            widgetDataRepository.refreshRemoteSnapshot()
            widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
