
package com.exapps.mangaworld.presentation.community
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



@HiltViewModel
class CommunityChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val communityRepository: CommunityRepository,
    private val sessionManager: FirebaseSessionManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager
) : ViewModel() {
    val roomId: String = java.net.URLDecoder.decode(savedStateHandle["roomId"] ?: "global", "UTF-8")
    val title: String = java.net.URLDecoder.decode(savedStateHandle["title"] ?: stringResource(R.string.live_chat), "UTF-8")
    val messages = communityRepository.observeChatMessages(roomId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            messages.collectLatest { latestMessages ->
                _suggestions.value = emptyList()
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.type_message)) })
                IconButton(onClick = { if (message.isNotBlank()) { viewModel.send(message); message = "" } }) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.accessibility_send), tint = MangaColors.Cyan)
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
