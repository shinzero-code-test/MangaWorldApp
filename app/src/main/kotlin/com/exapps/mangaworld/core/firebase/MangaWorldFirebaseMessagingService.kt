package com.exapps.mangaworld.core.firebase

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MangaWorldFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Phase 2 foundation: token is available for later user-device sync.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Phase 2 foundation: server-driven update notifications can be handled here.
    }
}
