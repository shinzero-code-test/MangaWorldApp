package com.exapps.mangaworld.presentation.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.ml.MlKitSmartReplySuggester
import com.exapps.mangaworld.core.ml.SmartReplyMessageInput
import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val communityRepository: CommunityRepository,
    private val smartReplySuggester: MlKitSmartReplySuggester,
    private val sessionManager: FirebaseSessionManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager
) : ViewModel() {
    val roomId: String = java.net.URLDecoder.decode(savedStateHandle["roomId"] ?: "global", "UTF-8")
    val title: String = java.net.URLDecoder.decode(savedStateHandle["title"] ?: "الدردشة المباشرة", "UTF-8")
    val messages = communityRepository.observeChatMessages(roomId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            combine(messages, remoteConfigManager.mlSmartReplyEnabled) { latestMessages, enabled ->
                latestMessages to enabled
            }.collectLatest { (latestMessages, enabled) ->
                if (!enabled) {
                    _suggestions.value = emptyList()
                    return@collectLatest
                }
                val currentUserId = sessionManager.currentUserId().orEmpty()
                val inputs = latestMessages.takeLast(10).map { message ->
                    SmartReplyMessageInput(
                        authorId = message.authorUid,
                        text = message.text,
                        isLocalUser = message.authorUid == currentUserId,
                        timestampMs = message.createdAt
                    )
                }
                val generated = runCatching { smartReplySuggester.suggestReplies(inputs) }.getOrDefault(emptyList())
                _suggestions.value = generated
                if (generated.isNotEmpty()) {
                    analyticsManager.logSmartReplySurface("community_chat", generated.size)
                }
            }
        }
    }

    fun send(text: String) {
        viewModelScope.launch { runCatching { communityRepository.sendChatMessage(roomId, text) } }
    }

    fun onSuggestionSelected(reply: String) {
        analyticsManager.logSmartReplySelected("community_chat", reply.length)
    }
}

@Composable
fun CommunityChatScreen(
    onBack: () -> Unit,
    viewModel: CommunityChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text(viewModel.title, style = MaterialTheme.typography.titleLarge, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(0.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { suggestion ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                message = suggestion
                                viewModel.onSuggestionSelected(suggestion)
                            },
                            label = { Text(suggestion) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f), label = { Text("اكتب رسالة") })
                IconButton(onClick = { if (message.isNotBlank()) { viewModel.send(message); message = "" } }) {
                    Icon(Icons.Filled.Send, null, tint = MangaColors.Cyan)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: CommunityChatMessage) {
    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Text(message.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
            Text(message.text, color = MangaColors.OnSurfaceVariant)
        }
    }
}
