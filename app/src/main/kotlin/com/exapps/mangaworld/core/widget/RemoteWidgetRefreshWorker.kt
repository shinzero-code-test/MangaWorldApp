package com.exapps.mangaworld.core.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.core.util.suspendRunCatching
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
        // U-13: both calls below are suspend. `suspendRunCatching` rethrows a
        // CancellationException instead of turning a cancelled refresh into a
        // retry/failure verdict (the old `runCatching` caught it and only
        // rethrew it later, after the fan-out had already been abandoned).
        return suspendRunCatching {
            widgetDataRepository.refreshRemoteSnapshot()
            widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
            Result.success()
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A persistent failure (all sources down, snapshot schema change)
            // must not retry the 18-source fan-out forever.
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }
}
