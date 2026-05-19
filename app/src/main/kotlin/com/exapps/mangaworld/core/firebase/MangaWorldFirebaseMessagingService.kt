package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MangaWorldFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Phase 2 foundation: token is available for later user-device sync.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "MangaWorld"
        val body = message.notification?.body ?: message.data["body"] ?: "لديك تحديث جديد"
        val intent = when {
            message.data["sourceId"] != null && message.data["slug"] != null ->
                AppLaunchIntents.detail(this, message.data.getValue("sourceId"), message.data.getValue("slug"))
            else -> AppLaunchIntents.latestUpdates(this)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            intent.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService<NotificationManager>()?.notify(message.messageId?.hashCode() ?: body.hashCode(), notification)
    }
}
