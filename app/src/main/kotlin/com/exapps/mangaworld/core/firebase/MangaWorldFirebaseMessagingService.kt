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
        val requestCode = (message.data["sourceId"] ?: "") + "_" + (message.data["slug"] ?: "") + "_" + (message.data["chapterUrl"] ?: "")
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode.hashCode().coerceAtLeast(1),
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
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setGroup("fcm_notifications")

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
                // Disjoint ID band: the old `% 1000` collided across manga and with
                // other channels' ranges, replacing unrelated notifications (M-review).
                val notificationId = NOTIF_ID_FCM_BASE + ((message.data["mangaId"] ?: body).hashCode() and 0x7FFFFFFF) % 100000
                getSystemService<NotificationManager>()?.notify(
                    notificationId,
                    notificationBuilder.build()
                )
            }
        }
    }

    /**
     * Download bitmap using the injected OkHttpClient (connection pooling, caching, interceptor)
     * instead of raw HttpURLConnection. Runs on IO thread to avoid ANR.
     *
     * Hardened: response capped at [MAX_IMAGE_BYTES] and the decoded bitmap is
     * downsampled to ≤[TARGET_DIMENSION]px — a hostile push pointing at a huge
     * image previously caused OOM-level decodes on low-end devices (M-review).
     */
    private suspend fun loadNotificationBitmap(imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val declared = resp.body?.contentLength() ?: -1L
                    if (declared > MAX_IMAGE_BYTES) return@use null

                    val bytes = resp.body!!.byteStream().use { input ->
                        val buffer = java.io.ByteArrayOutputStream(minOf(declared.takeIf { it > 0 } ?: 64_000L, MAX_IMAGE_BYTES).toInt())
                        val chunk = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read == -1) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) return@use null
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    }

                    // Bounds pass → sample size → memory-bounded decode.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= TARGET_DIMENSION ||
                        bounds.outHeight / (sample * 2) >= TARGET_DIMENSION) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            }.onFailure { e ->
                Log.w("MessagingService", "Failed to load notification image: ${e.message}")
            }.getOrNull()
        }
    }

    private companion object {
        /** 2 MB hard cap on push-image downloads. */
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        /** Big-picture decode target (longest edge) — notifications never render larger. */
        const val TARGET_DIMENSION = 1024
        /** Disjoint from download IDs [1000..9999] and local notification ranges. */
        const val NOTIF_ID_FCM_BASE = 200000
    }
}
