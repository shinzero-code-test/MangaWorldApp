package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.exapps.mangaworld.MangaWorldApp
import com.exapps.mangaworld.core.data.NotificationCenterStore
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MangaWorldFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var messagingRegistrar: FirebaseMessagingRegistrar
    @Inject lateinit var analyticsManager: FirebaseAnalyticsManager
    @Inject lateinit var notificationPolicyManager: NotificationPolicyManager
    @Inject lateinit var bitmapLoader: NotificationBitmapLoader
    @Inject lateinit var settingsRepository: com.exapps.mangaworld.domain.repository.SettingsRepository

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

        val title = message.notification?.title ?: message.data["title"]
            ?: getString(com.exapps.mangaworld.R.string.fcm_default_title)
        val body = message.notification?.body ?: message.data["body"]
            ?: getString(com.exapps.mangaworld.R.string.fcm_default_body)
        val type = message.data["type"] ?: "generic"
        val mangaId = message.data["mangaId"]
        val imageUrl = message.notification?.imageUrl?.toString() ?: message.data["imageUrl"]

        // Check if manga is muted
        if (mangaId != null && notificationPolicyManager.isMangaMuted(mangaId)) {
            return
        }

        // Granular community toggles (settings screen): drop muted categories
        // before building anything. Types come from the dashboard push routes.
        serviceScope.launch {
            runCatching {
                val settings = settingsRepository.getAppSettings().first()
                if (!isPushAllowed(type, settings)) return@launch
                showNotification(message, title, body, type, mangaId, imageUrl)
            }
        }
    }

    private fun isPushAllowed(
        type: String,
        settings: com.exapps.mangaworld.domain.model.AppSettings
    ): Boolean {
        if (!settings.enableNotifications) return false
        return when (type.uppercase()) {
            "REPLY", "MENTION", "COMMENT_THREAD", "CHAT_MENTION" -> settings.notifyComments
            "REVIEW_REACTION" -> settings.notifyLikes
            "FOLLOW", "FOLLOWER" -> settings.notifyFollowers
            else -> true
        }
    }

    private fun showNotification(
        message: RemoteMessage,
        title: String,
        body: String,
        type: String,
        mangaId: String?,
        imageUrl: String?
    ) {

        val intent = when {
            // FOLLOW carries the follower uid instead of manga linkage — open
            // their public profile, never a blank detail screen.
            type.equals("FOLLOW", ignoreCase = true) && !message.data["targetUid"].isNullOrBlank() ->
                AppLaunchIntents.profile(this, message.data.getValue("targetUid"))

            message.data["sourceId"] != null && message.data["slug"] != null ->
                AppLaunchIntents.detail(this, message.data.getValue("sourceId"), message.data.getValue("slug"))

            else -> AppLaunchIntents.latestUpdates(this)
        }
        val requestCode = (message.data["sourceId"] ?: "") + "_" + (message.data["slug"] ?: "") + "_" + (message.data["chapterUrl"] ?: "") + "_" + (message.data["targetUid"] ?: "")
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

        // v8 (#11): persist EVERY displayed push to the Notification Centre —
        // previously only local workers logged entries there, so cloud pushes
        // were invisible in history.
        logToNotificationCenter(title, body, mangaId)

        // Load bitmap on IO thread to avoid blocking the main thread (ANR)
        serviceScope.launch {
            val bitmap = bitmapLoader.load(imageUrl)
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
     * Persist a displayed push into the shared `local_notifications` ring buffer
     * via [NotificationCenterStore] (mutex-serialized). Type "push" keeps the
     * Notification Centre's mark-all-read logic treating these as local entries.
     */
    private fun logToNotificationCenter(title: String, body: String, mangaId: String?) {
        serviceScope.launch {
            runCatching {
                NotificationCenterStore.update(this@MangaWorldFirebaseMessagingService) { arr ->
                    val obj = org.json.JSONObject().apply {
                        put("id", "push_${System.currentTimeMillis()}_${(title + body).hashCode()}")
                        put("title", title)
                        put("body", body)
                        put("type", "push")
                        if (mangaId != null) put("mangaId", mangaId)
                        put("read", false)
                        put("timestamp", System.currentTimeMillis())
                    }
                    arr.put(obj)
                    // Keep only last 100 local notifications (same cap as workers).
                    while (arr.length() > 100) { arr.remove(0) }
                }
            }.onFailure { e ->
                // Was silent: a full/corrupt ring buffer then drops pushes
                // with zero signal (#26).
                Log.w("MWFMessaging", "notification-center log failed", e)
            }
        }
    }

    private companion object {
        /** Disjoint from download IDs [1000..9999] and local notification ranges. */
        const val NOTIF_ID_FCM_BASE = 200000
    }
}
