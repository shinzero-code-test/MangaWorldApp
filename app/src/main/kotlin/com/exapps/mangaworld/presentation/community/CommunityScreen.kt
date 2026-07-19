import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.firebase.filterMutedComments
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CommunityTab { COMMENTS, REVIEWS }

data class CommentThread(
    val comment: CommunityComment,
    val depth: Int = 0,
    val nestedReplies: List<CommentThread> = emptyList()
)

@Stable
data class CommunityUiState(
    val title: String = stringResource(R.string.community_title),
    val comments: List<CommunityComment> = emptyList(),
    val reviews: List<MangaReview> = emptyList(),
    val profile: CommunityProfile? = null,
    val appSettings: AppSettings = AppSettings(),
    val tab: CommunityTab = CommunityTab.COMMENTS,
    val chapterMode: Boolean = false,
    val focusCommentId: String? = null,
    val replyTo: CommunityComment? = null,
    val error: String? = null
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val communityRepository: CommunityRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: FirebaseSessionManager,
    private val analyticsManager: FirebaseAnalyticsManager,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val mangaCacheDao: com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
) : ViewModel() {
    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])
    private val slug: String = checkNotNull(savedStateHandle["slug"])
    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val chapterUrl: String? = savedStateHandle.get<String>("chapterUrl")?.takeIf { it.isNotBlank() }
    private val focusCommentId: String? = savedStateHandle.get<String>("commentId")?.takeIf { it.isNotBlank() }

    private val _tab = MutableStateFlow(if (chapterUrl == null) CommunityTab.REVIEWS else CommunityTab.COMMENTS)
    private val mangaTitle: StateFlow<String> = flow {
        val cached = mangaCacheDao.get(mangaId)
        emit(cached?.title ?: slug)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, slug)

    private val _replyTo = MutableStateFlow<CommunityComment?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val commentsFlow = if (chapterUrl == null) communityRepository.observeMangaComments(mangaId) else communityRepository.observeChapterComments(mangaId, chapterUrl.orEmpty())
    private val reviewsFlow = if (chapterUrl == null) communityRepository.observeReviews(mangaId) else flowOf(emptyList())
    private val profileFlow: Flow<CommunityProfile?> = flow { emit(communityRepository.getCurrentProfile()) }
    private val appSettingsFlow = settingsRepository.getAppSettings()

    val state: StateFlow<CommunityUiState> = combine(
        combine(commentsFlow, reviewsFlow, profileFlow, appSettingsFlow) { c, r, p, a -> Quadruple(c, r, p, a) },
        _tab, _replyTo, _error, mangaTitle
    ) { q, tab, replyTo, error, title ->
        CommunityUiState(
            title = if (chapterUrl == null) stringResource(R.string.fmt_068, title) else stringResource(R.string.community_discussion),
            comments = filterMutedComments(q.first, q.fourth.mutedUserIds),
            reviews = q.second,
            profile = q.third,
            appSettings = q.fourth,
            tab = tab,
            chapterMode = chapterUrl != null,
            focusCommentId = focusCommentId,
            replyTo = replyTo,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CommunityUiState(
        title = if (chapterUrl == null) stringResource(R.string.community_title) else stringResource(R.string.community_discussion),
        chapterMode = chapterUrl != null
    ))

    fun setTab(tab: CommunityTab) { _tab.value = tab }
    fun setReply(comment: CommunityComment?) { _replyTo.value = comment }

    fun postComment(text: String, spoiler: Boolean) {
        viewModelScope.launch {
            runCatching {
                val replyPrefix = _replyTo.value?.let { "@${it.authorUsername.ifBlank { it.authorName }} " } ?: ""
                val fullText = replyPrefix + text
                if (chapterUrl == null) communityRepository.postMangaComment(mangaId, slug, sourceId, fullText, spoiler, _replyTo.value?.id)
                else communityRepository.postChapterComment(mangaId, slug, sourceId, chapterUrl, fullText, spoiler, _replyTo.value?.id)
            }.onSuccess { _replyTo.value = null; _error.value = null }
                .onFailure { e -> _error.value = e.message ?: stringResource(R.string.community_error_post) }
        }
    }

    fun upsertReview(rating: Int, title: String, body: String) {
        viewModelScope.launch {
            runCatching { communityRepository.upsertReview(mangaId, slug, sourceId, rating, title, body) }
                .onFailure { e -> _error.value = e.message ?: stringResource(R.string.str_338) }
        }
    }

    fun reportComment(comment: CommunityComment, reason: String) {
        viewModelScope.launch {
            runCatching { communityRepository.reportComment(comment, reason) }
                .onFailure { e -> _error.value = e.message ?: stringResource(R.string.community_error_report) }
        }
    }

    fun muteUser(uid: String) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings().first().mutedUserIds
            settingsRepository.setMutedUserIds(current + uid)
        }
    }

    /** Build threaded view: top-level comments with nested replies up to 3 levels deep. */
    fun buildThreadedComments(allComments: List<CommunityComment>): List<CommentThread> {
        val children = allComments.groupBy { it.parentId }
        return allComments.filter { it.parentId == null }.map { parent ->
            CommentThread(comment = parent, depth = 0, nestedReplies = buildReplies(children, parent.id, 1))
        }
    }

    private fun buildReplies(children: Map<String?, List<CommunityComment>>, parentId: String, depth: Int): List<CommentThread> {
        if (depth > 3) return emptyList()
        return children[parentId]?.sortedBy { it.createdAt }?.map { reply ->
            CommentThread(comment = reply, depth = depth, nestedReplies = buildReplies(children, reply.id, depth + 1))
        } ?: emptyList()
    }

    fun likeComment(commentId: String) {
        viewModelScope.launch { runCatching { communityRepository.likeComment(commentId) } }
    }

    fun dislikeComment(commentId: String) {
        viewModelScope.launch { runCatching { communityRepository.dislikeComment(commentId) } }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: CommunityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var commentText by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    var reviewTitle by remember { mutableStateOf("") }
    var reviewBody by remember { mutableStateOf("") }
    var reviewRating by remember { mutableStateOf(5) }
    var reportTarget by remember { mutableStateOf<CommunityComment?>(null) }
    var reportReason by remember { mutableStateOf("") }
    val expandedSpoilers = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.comments, state.focusCommentId) {
        val target = state.focusCommentId ?: return@LaunchedEffect
        val index = state.comments.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(state.title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Tab selector
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.tab == CommunityTab.COMMENTS,
                    onClick = { viewModel.setTab(CommunityTab.COMMENTS) },
                    label = { Text(stringResource(R.string.comments)) },
                    shape = RoundedCornerShape(10.dp)
                )
                FilterChip(
                    selected = state.tab == CommunityTab.REVIEWS,
                    onClick = { viewModel.setTab(CommunityTab.REVIEWS) },
                    label = { Text(stringResource(R.string.community_reviews)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }

            when (state.tab) {
                CommunityTab.COMMENTS -> {
                    val threaded = remember(state.comments) { viewModel.buildThreadedComments(state.comments) }
                    val flat = remember(threaded) {
                        val result = mutableListOf<Pair<CommentThread, Int>>()
                        fun flatten(t: CommentThread, d: Int) { result.add(t to d); t.nestedReplies.forEach { flatten(it, d + 1) } }
                        threaded.forEach { flatten(it, 0) }; result
                    }

                    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (flat.isEmpty()) {
                            item { Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.community_no_comments), color = MangaColors.Muted) } }
                        }
                        items(flat.size, key = { flat[it].first.comment.id }) { idx ->
                            val (thread, depth) = flat[idx]
                            val comment = thread.comment
                            Column(modifier = Modifier.padding(start = (depth * 20).dp)) {
                                if (depth > 0) {
                                    Box(Modifier.padding(start = 14.dp, bottom = 2.dp).size(width = 2.dp, height = 12.dp).background(MangaColors.Cyan.copy(alpha = 0.3f)))
                                }
                                CommentCard(
                                    comment = comment, depth = depth,
                                    isFocused = state.focusCommentId == comment.id,
                                    isSpoilerRevealed = comment.id in expandedSpoilers,
                                    spoilerDefault = state.appSettings.spoilerCollapseDefault,
                                    onRevealSpoiler = { expandedSpoilers.add(comment.id) },
                                    onReply = { viewModel.setReply(comment) },
                                    onReport = { reportTarget = comment; reportReason = "" },
                                    onMute = { viewModel.muteUser(comment.authorUid) },
                                    onProfileClick = { onOpenProfile(comment.authorUid) },
                                    onLike = { viewModel.likeComment(comment.id) },
                                    onDislike = { viewModel.dislikeComment(comment.id) }
                                )
                            }
                        }
                    }

                    // Reply indicator
                    state.replyTo?.let { reply ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.Cyan.copy(alpha = 0.1f))) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Reply, null, tint = MangaColors.Cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.fmt_050, reply.authorUsername.ifBlank { reply.authorName ), color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { viewModel.setReply(null) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = MangaColors.Muted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Comment input
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = commentText, onValueChange = { commentText = it },
                                    modifier = Modifier.weight(1f), placeholder = { Text(stringResource(R.string.community_add_comment)) },
                                    shape = RoundedCornerShape(10.dp), maxLines = 3
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { spoiler = !spoiler }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (spoiler) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, tint = if (spoiler) MangaColors.Yellow else MangaColors.Muted, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { if (commentText.isNotBlank()) { viewModel.postComment(commentText.trim(), spoiler); commentText = ""; spoiler = false } }, enabled = commentText.isNotBlank()) {
                                    Icon(Icons.Filled.Send, null, tint = MangaColors.Cyan)
                                }
                            }
                        }
                    }
                }
                CommunityTab.REVIEWS -> {
                    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Card(Modifier.fillMaxWidth().clickable { reviewDialog = true }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.Cyan.copy(alpha = 0.1f))) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, null, tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.community_add_review), color = MangaColors.Cyan, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (state.reviews.isEmpty()) {
                            item { Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.community_no_reviews), color = MangaColors.Muted) } }
                        }
                        items(state.reviews, key = { it.id }) { review ->
                            ReviewCard(review = review, onProfileClick = { onOpenProfile(review.authorUid) })
                        }
                    }
                }
            }

            state.error?.let {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.Error.copy(alpha = 0.1f))) {
                    Text(it, color = MangaColors.Error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // Review dialog
    if (reviewDialog) {
        AlertDialog(onDismissRequest = { reviewDialog = false }, containerColor = MangaColors.Surface, title = { Text(stringResource(R.string.community_review_body_hint), color = MangaColors.OnSurface) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = reviewTitle, onValueChange = { reviewTitle = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.community_review_title_hint)) }, shape = RoundedCornerShape(10.dp))
                OutlinedTextField(value = reviewBody, onValueChange = { reviewBody = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.review_details)) }, minLines = 4, shape = RoundedCornerShape(10.dp))
                Text(stringResource(R.string.rating), color = MangaColors.OnSurface, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { star -> FilterChip(selected = reviewRating == star, onClick = { reviewRating = star }, label = { Text("$star") }, shape = RoundedCornerShape(8.dp)) } }
            }},
            confirmButton = { Button(onClick = { viewModel.upsertReview(reviewRating, reviewTitle, reviewBody); reviewDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { reviewDialog = false }) { Text(stringResource(R.string.cancel), color = MangaColors.Muted) } }
        )
    }

    // Report dialog
    reportTarget?.let { comment ->
        AlertDialog(onDismissRequest = { reportTarget = null }, containerColor = MangaColors.Surface, title = { Text(stringResource(R.string.community_report_title), color = MangaColors.OnSurface) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(comment.text, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = reportReason, onValueChange = { reportReason = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.community_report_reason)) }, shape = RoundedCornerShape(10.dp))
            }},
            confirmButton = { Button(onClick = { viewModel.reportComment(comment, reportReason); reportTarget = null }, enabled = reportReason.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)) { Text(stringResource(R.string.community_send)) } },
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text(stringResource(R.string.cancel), color = MangaColors.Muted) } }
        )
    }
}

@Composable
private fun CommentCard(
    comment: CommunityComment, depth: Int = 0,
    isFocused: Boolean, isSpoilerRevealed: Boolean, spoilerDefault: Boolean,
    onRevealSpoiler: () -> Unit, onReply: () -> Unit, onReport: () -> Unit,
    onMute: () -> Unit, onProfileClick: () -> Unit,
    onLike: () -> Unit, onDislike: () -> Unit
) {
    var showOverflow by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (isFocused) MangaColors.GlowPurple else MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: avatar + name + overflow menu
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // User avatar
                    if (!comment.authorAvatarUrl.isNullOrBlank()) {
                        AsyncImage(model = comment.authorAvatarUrl, contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
                    } else {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(MangaColors.Primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Text(comment.authorName.take(1), color = MangaColors.Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Column {
                        Text(comment.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onProfileClick))
                        Text(comment.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
                // Overflow menu
                Box {
                    IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        Text(stringResource(R.string.community_reply), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { onReply(); showOverflow = false })
                        Text(stringResource(R.string.community_report), color = MangaColors.Yellow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { onReport(); showOverflow = false })
                        Text(stringResource(R.string.community_mute), color = MangaColors.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { onMute(); showOverflow = false })
                    }
                }
            }

            // Content
            if (comment.spoiler && spoilerDefault && !isSpoilerRevealed) {
                OutlinedButton(onClick = onRevealSpoiler, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.community_show_spoiler))
                }
            } else {
                Text(comment.text, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }

            // Actions: Like/Dislike + reply count
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onLike, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ThumbUp, null, tint = MangaColors.Cyan, modifier = Modifier.size(16.dp)) }
                    Text("${comment.likes}", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onDislike, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ThumbDown, null, tint = MangaColors.Muted, modifier = Modifier.size(16.dp)) }
                    Text("${comment.dislikes}", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (comment.replyCount > 0) {
                    Text(stringResource(R.string.fmt_025, comment.replyCount), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(review: MangaReview, onProfileClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!review.authorAvatarUrl.isNullOrBlank()) {
                    AsyncImage(model = review.authorAvatarUrl, contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(MangaColors.Yellow.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, null, tint = MangaColors.Yellow, modifier = Modifier.size(16.dp))
                    }
                }
                Column {
                    Text(review.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onProfileClick))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${review.rating}/5", color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("•", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                        Text(review.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (review.title.isNotBlank()) Text(review.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (review.body.isNotBlank()) Text(review.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
