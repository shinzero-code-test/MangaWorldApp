package com.exapps.mangaworld.core.ml

import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.flow.first
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
class MlKitSmartReplySuggester @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    suspend fun suggestReplies(messages: List<SmartReplyMessageInput>): List<String> {
        if (!settingsRepository.getAppSettings().first().mlKitEnabled) return emptyList()
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
