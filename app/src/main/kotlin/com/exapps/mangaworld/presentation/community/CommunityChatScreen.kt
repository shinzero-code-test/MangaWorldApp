package com.exapps.mangaworld.presentation.community
import com.exapps.mangaworld.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.theme.LocalizedText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val communityRepository: CommunityRepository,
    private val sessionManager: FirebaseSessionManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager
) : ViewModel() {
    val roomId: String = java.net.URLDecoder.decode(savedStateHandle["roomId"] ?: "global", "UTF-8")
    val title: String = java.net.URLDecoder.decode(savedStateHandle["title"] ?: context.getString(R.string.live_chat), "UTF-8")
    val messages = communityRepository.observeChatMessages(roomId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _lastSentAt = MutableStateFlow<Long?>(null)
    val lastSentAt = _lastSentAt.asStateFlow()

    init {
        viewModelScope.launch {
            messages.collectLatest { latestMessages ->
                _suggestions.value = emptyList()
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        // NonCancellable + surfaced errors: the old runCatching swallowed
        // every failure (offline, denied, signed-out) with zero feedback
        // while the input had already been cleared — a silent message loss.
        viewModelScope.launch(NonCancellable) {
            try {
                communityRepository.sendChatMessage(roomId, text.trim())
                _error.value = null
                _lastSentAt.value = System.currentTimeMillis()
            } catch (throwable: kotlinx.coroutines.CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                _error.value = throwable.message
                    ?: context.getString(R.string.community_error_generic_action)
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun onSuggestionSelected(reply: String) {
        analyticsManager.logSmartReplySelected("community_chat", reply.length)
    }
}

@Composable
fun CommunityChatScreen(
    onBack: () -> Unit,
    isSignedIn: Boolean = true,
    viewModel: CommunityChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lastSentAt by viewModel.lastSentAt.collectAsStateWithLifecycle()
    var message by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Clear-on-success: the draft survives until the write lands.
    LaunchedEffect(lastSentAt) {
        if (lastSentAt != null) message = ""
    }
    LaunchedEffect(error) {
        if (error != null) {
            scope.launch {
                snackbar.showSnackbar(error!!)
                viewModel.dismissError()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_back), tint = MangaColors.OnSurface) }
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
            // Guests: composer hidden — RTDB rules would reject the write anyway.
            if (isSignedIn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.type_message)) })
                    IconButton(onClick = { if (message.isNotBlank()) viewModel.send(message) }) {
                        Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.accessibility_send), tint = MangaColors.Cyan)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.reader_sign_in_to_participate),
                    color = MangaColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ChatBubble(message: CommunityChatMessage) {
    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LocalizedText(message.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Text(message.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
            LocalizedText(message.text, color = MangaColors.OnSurfaceVariant)
        }
    }
}
