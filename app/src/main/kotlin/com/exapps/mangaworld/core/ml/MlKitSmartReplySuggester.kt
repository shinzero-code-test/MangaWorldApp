package com.exapps.mangaworld.core.ml

import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class SmartReplyMessageInput(
    val authorId: String,
    val text: String,
    val isLocalUser: Boolean,
    val timestampMs: Long
)

@Singleton
class MlKitSmartReplySuggester @Inject constructor() {

    suspend fun suggestReplies(messages: List<SmartReplyMessageInput>): List<String> {
        if (messages.size < 2) return emptyList()

        val conversation = messages.takeLast(10).map { message ->
            if (message.isLocalUser) {
                TextMessage.createForLocalUser(message.text, message.timestampMs)
            } else {
                TextMessage.createForRemoteUser(message.text, message.timestampMs, message.authorId)
            }
        }

        val result = SmartReply.getClient().suggestReplies(conversation).await()
        return if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
            result.suggestions.map { it.text }.distinct().take(3)
        } else {
            emptyList()
        }
    }
}
