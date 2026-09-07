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

    private fun topicFor(mangaId: String): String {
        val sanitized = mangaId.replace(Regex("[^A-Za-z0-9-_.~%]"), "_")
        // Short hash suffix: two long IDs sharing a 200-char prefix previously
        // aliased onto one topic after take(). (Old exact-name topics leak
        // server-side but resubscription self-heals delivery.)
        val hash = runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(mangaId.toByteArray())
            digest.take(4).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: mangaId.hashCode().toString()
        return "manga_${sanitized.take(180)}_$hash".take(MAX_TOPIC_LENGTH)
    }
}
