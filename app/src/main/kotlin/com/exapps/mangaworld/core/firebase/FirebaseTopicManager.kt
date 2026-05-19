package com.exapps.mangaworld.core.firebase

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTopicManager @Inject constructor() {
    private val messaging = FirebaseMessaging.getInstance()

    suspend fun subscribeToManga(mangaId: String) {
        messaging.subscribeToTopic(topicFor(mangaId)).await()
    }

    suspend fun unsubscribeFromManga(mangaId: String) {
        messaging.unsubscribeFromTopic(topicFor(mangaId)).await()
    }

    private fun topicFor(mangaId: String): String = "manga_${mangaId.replace(Regex("[^A-Za-z0-9-_.~%]"), "_")}"
}
