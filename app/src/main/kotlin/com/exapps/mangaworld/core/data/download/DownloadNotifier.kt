package com.exapps.mangaworld.core.data.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.local.entity.DownloadBatchEntity
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-33 seam (companion of [DownloadWorkScheduler]): the batch-summary
 * notification the queue publishes when a whole-manga download reaches a
 * terminal state.
 *
 * Why it exists: building a `NotificationCompat` touches `android.app.Notification`,
 * which is stubs-only on the JVM — so `reconcileBatchCompletion` (the terminal
 * notification path) could not be executed in a unit test at all. Behind this
 * interface the manager's "claim exactly once, then publish" contract is
 * verifiable, and the notification-building code stays where it belongs.
 */
interface DownloadNotifier {
    /** Publishes the terminal summary for a finished download batch. */
    fun notifyBatchComplete(batch: DownloadBatchEntity)
}

/** Production [DownloadNotifier]: a real system notification. */
@Singleton
class NotificationDownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : DownloadNotifier {

    override fun notifyBatchComplete(batch: DownloadBatchEntity) {
        val hasFailures = batch.failedChapters > 0
        val notification = NotificationCompat.Builder(context, MangaWorldApp.COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_downloads)
            .setContentTitle(
                context.getString(
                    if (hasFailures) R.string.download_notification_batch_partial_title
                    else R.string.download_notification_batch_complete_title,
                    batch.mangaTitle
                )
            )
            .setContentText(
                if (hasFailures) {
                    context.getString(
                        R.string.download_notification_batch_partial_body,
                        batch.completedChapters,
                        batch.failedChapters
                    )
                } else {
                    context.getString(
                        R.string.download_notification_batch_complete_body,
                        batch.completedChapters
                    )
                }
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    (batch.id + "complete").hashCode(),
                    AppLaunchIntents.downloads(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DownloadNotifIds.batchId(batch.id), notification)
    }
}
