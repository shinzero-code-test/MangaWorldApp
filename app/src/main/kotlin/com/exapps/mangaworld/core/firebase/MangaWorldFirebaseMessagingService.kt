package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import javax.inject.Inject

@AndroidEntryPoint
class MangaWorldFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var messagingRegistrar: FirebaseMessagingRegistrar
    @Inject lateinit var analyticsManager: FirebaseAnalyticsManager
    @Inject lateinit var notificationPolicyManager: NotificationPolicyManager
    @Inject lateinit var okHttpClient: OkHttpClient

    /** Reuse the application scope to avoid leaking coroutine scopes per token refresh. */
    private val serviceScope: CoroutineScope
        get() = (application as MangaWorldApp).applicationScope

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            runCatching { messagingRegistrar.onTokenRefreshed(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "MangaWorld"
        val body = message.notification?.body ?: message.data["body"] ?: "لديك تحديث جديد"
        val type = message.data["type"] ?: "generic"
        val mangaId = message.data["mangaId"]
        val imageUrl = message.notification?.imageUrl?.toString() ?: message.data["imageUrl"]

        // Check if manga is muted
        if (mangaId != null && notificationPolicyManager.isMangaMuted(mangaId)) {
            return
        }

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
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Load bitmap on IO thread to avoid blocking the main thread (ANR)
        serviceScope.launch {
            val bitmap = loadNotificationBitmap(imageUrl)
            withContext(Dispatchers.Main) {
                bitmap?.let {
                    notificationBuilder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(it)
                            .setBigContentTitle(title)
                            .setSummaryText(body)
                    )
                }
                analyticsManager.logNotificationReceived(type = type, hasImage = imageUrl != null)
                getSystemService<NotificationManager>()?.notify(
                    message.messageId?.hashCode() ?: body.hashCode(),
                    notificationBuilder.build()
                )
            }
        }
    }

    /**
     * Download bitmap using the injected OkHttpClient (connection pooling, caching, interceptor)
     * instead of raw HttpURLConnection. Runs on IO thread to avoid ANR.
     */
    private suspend fun loadNotificationBitmap(imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    val body = resp.body ?: return@use null
                    val stream: InputStream = body.byteStream()
                    stream.use(BitmapFactory::decodeStream)
                }
            }.onFailure { e ->
                Log.w("MessagingService", "Failed to load notification image: ${e.message}")
            }.getOrNull()
        }
    }
}
