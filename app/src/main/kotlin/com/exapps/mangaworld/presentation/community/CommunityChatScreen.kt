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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityChatViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {
    val messages = communityRepository.observeChatMessages("global")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun send(text: String) {
        viewModelScope.launch { runCatching { communityRepository.sendChatMessage("global", text) } }
    }
}

@Composable
fun CommunityChatScreen(
    onBack: () -> Unit,
    viewModel: CommunityChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text("الدردشة المباشرة", style = MaterialTheme.typography.titleLarge, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(0.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f), label = { Text("اكتب رسالة") })
            IconButton(onClick = { if (message.isNotBlank()) { viewModel.send(message); message = "" } }) {
                Icon(Icons.Filled.Send, null, tint = MangaColors.Cyan)
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
