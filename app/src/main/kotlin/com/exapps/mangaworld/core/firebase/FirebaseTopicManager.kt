package com.exapps.mangaworld.core.firebase

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TopicManager"
private const val MAX_TOPIC_LENGTH = 200

@Singleton
class FirebaseTopicManager @Inject constructor() {
    private val messaging = FirebaseMessaging.getInstance()

    suspend fun subscribeToManga(mangaId: String) {
        runCatching { messaging.subscribeToTopic(topicFor(mangaId)).await() }
            .onFailure { e -> Log.w(TAG, "Failed to subscribe to topic for $mangaId: ${e.message}") }
    }

    suspend fun unsubscribeFromManga(mangaId: String) {
        runCatching { messaging.unsubscribeFromTopic(topicFor(mangaId)).await() }
            .onFailure { e -> Log.w(TAG, "Failed to unsubscribe from topic for $mangaId: ${e.message}") }
    }

    private fun topicFor(mangaId: String): String =
        "manga_${mangaId.replace(Regex("[^A-Za-z0-9-_.~%]"), "_")}".take(MAX_TOPIC_LENGTH)
}
