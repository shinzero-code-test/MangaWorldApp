package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL
import javax.inject.Inject

@AndroidEntryPoint
class MangaWorldFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var messagingRegistrar: FirebaseMessagingRegistrar
    @Inject lateinit var analyticsManager: FirebaseAnalyticsManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { messagingRegistrar.onTokenRefreshed(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "MangaWorld"
        val body = message.notification?.body ?: message.data["body"] ?: "لديك تحديث جديد"
        val type = message.data["type"] ?: "generic"
        val imageUrl = message.notification?.imageUrl?.toString() ?: message.data["imageUrl"]
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

        val notificationBuilder = NotificationCompat.Builder(this, MangaWorldApp.CLOUD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        loadNotificationBitmap(imageUrl)?.let { bitmap ->
            notificationBuilder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(body)
            )
        }

        analyticsManager.logNotificationReceived(type = type, hasImage = imageUrl != null)
        getSystemService<NotificationManager>()?.notify(message.messageId?.hashCode() ?: body.hashCode(), notificationBuilder.build())
    }

    private fun loadNotificationBitmap(imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank()) return null
        return runCatching {
            URL(imageUrl).openStream().use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
}
