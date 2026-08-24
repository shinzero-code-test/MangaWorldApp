package com.exapps.mangaworld.presentation.community

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.firebase.filterMutedComments
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CommunityReplyTarget
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ReplyRecipient(
    val id: String,
    val authorUid: String,
    val username: String,
    val displayName: String
)

@Immutable
data class RepliesUiState(
    val root: CommunityTarget? = null,
    val replies: List<CommunityComment> = emptyList(),
    val replyTo: ReplyRecipient? = null,
    val profile: CommunityProfile? = null,
    val appSettings: AppSettings = AppSettings(),
    val error: String? = null
)

@HiltViewModel
class RepliesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val communityRepository: CommunityRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])
    private val slug: String = checkNotNull(savedStateHandle["slug"])
    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val rootId: String = checkNotNull(savedStateHandle["rootId"])
    private val reviewId: String? = savedStateHandle.get<String>("reviewId")?.takeIf { it.isNotBlank() }
    private val chapterUrl: String? = decodeCommunityRouteArgument(savedStateHandle.get<String>("chapterUrl"))
    private val selectedRecipientId = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val commentsFlow = if (chapterUrl == null) {
        communityRepository.observeMangaComments(mangaId)
    } else {
        communityRepository.observeChapterComments(mangaId, chapterUrl)
    }
    private val reviewsFlow = if (reviewId == null) flowOf(emptyList()) else communityRepository.observeReviews(mangaId)
    private val profileFlow: Flow<CommunityProfile?> = flow { emit(communityRepository.getCurrentProfile()) }

    val state: StateFlow<RepliesUiState> = combine(
        combine(commentsFlow, reviewsFlow, profileFlow, settingsRepository.getAppSettings()) { comments, reviews, profile, settings ->
            ReplyData(comments, reviews, profile, settings)
        },
        selectedRecipientId,
        error
    ) { data, selectedId, currentError ->
        val root = if (reviewId == null) {
            data.comments.firstOrNull { it.id == rootId }?.let { CommunityTarget.Comment(it) }
        } else {
            data.reviews.firstOrNull { it.id == reviewId }?.let { CommunityTarget.Review(it) }
        }
        val replies = filterMutedComments(
            if (reviewId == null) flattenLegacyAndFlatReplies(data.comments, rootId)
            else data.comments.filter { it.reviewId == reviewId }.sortedBy { it.createdAt },
            data.settings.mutedUserIds
        )
        val rootRecipient = root?.toRecipient()
        val recipients = listOfNotNull(rootRecipient) + replies.map(CommunityComment::toRecipient)
        RepliesUiState(
            root = root,
            replies = replies,
            replyTo = recipients.firstOrNull { it.id == selectedId } ?: rootRecipient,
            profile = data.profile,
            appSettings = data.settings,
            error = currentError
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RepliesUiState())

    fun selectReplyTarget() {
        // New replies always default to the root author. Users can explicitly target someone
        // else by typing @username in the composer. (Parameter removed — it was ignored.)
        selectedRecipientId.value = null
    }

    fun postReply(text: String, spoiler: Boolean) {
        val rootRecipient = state.value.root?.toRecipient() ?: return
        val mentionsUser = USER_MENTION.containsMatchIn(text)
        val replyTarget = CommunityReplyTarget(
            parentId = rootId.takeIf { reviewId == null },
            reviewId = reviewId,
            replyToUid = rootRecipient.authorUid.takeIf { !mentionsUser },
            replyToUsername = rootRecipient.username.takeIf { !mentionsUser && it.isNotBlank() }
        )
        launchAction(R.string.community_error_post) {
            if (chapterUrl == null) {
                communityRepository.postMangaComment(mangaId, slug, sourceId, text, spoiler, replyTarget)
            } else {
                communityRepository.postChapterComment(mangaId, slug, sourceId, chapterUrl, text, spoiler, replyTarget)
            }
            selectedRecipientId.value = null
        }
    }

    fun updateComment(comment: CommunityComment, text: String, spoiler: Boolean) = launchAction(R.string.community_error_post) {
        communityRepository.updateComment(comment, text, spoiler)
    }

    fun deleteComment(comment: CommunityComment) = launchAction(R.string.error_generic) {
        communityRepository.deleteComment(comment)
    }

    fun deleteReview(review: MangaReview) = launchAction(R.string.error_generic) {
        communityRepository.deleteReview(review)
    }

    fun upsertReview(rating: Int, title: String, body: String) = launchAction(R.string.str_338) {
        communityRepository.upsertReview(mangaId, slug, sourceId, rating, title, body)
    }

    fun report(target: CommunityTarget, reason: String) = launchAction(R.string.community_error_report) {
        when (target) {
            is CommunityTarget.Comment -> communityRepository.reportComment(target.value, reason)
            is CommunityTarget.Review -> communityRepository.reportReview(target.value, reason)
        }
    }

    fun muteUser(uid: String) = viewModelScope.launch {
        val muted = settingsRepository.getAppSettings().first().mutedUserIds
        settingsRepository.setMutedUserIds(muted + uid)
    }

    fun likeComment(commentId: String) = launchAction(R.string.community_error_vote) {
        communityRepository.likeComment(commentId)
    }

    fun dislikeComment(commentId: String) = launchAction(R.string.community_error_vote) {
        communityRepository.dislikeComment(commentId)
    }

    fun likeReview(review: MangaReview) = launchAction(R.string.community_error_vote) {
        communityRepository.likeReview(review.mangaId, review.id)
    }

    fun dislikeReview(review: MangaReview) = launchAction(R.string.community_error_vote) {
        communityRepository.dislikeReview(review.mangaId, review.id)
    }

    private fun launchAction(fallbackRes: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                error.value = null
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                error.value = throwable.message ?: context.getString(fallbackRes)
            }
        }
    }

    private data class ReplyData(
        val comments: List<CommunityComment>,
        val reviews: List<MangaReview>,
        val profile: CommunityProfile?,
        val settings: AppSettings
    )

    private companion object {
        val USER_MENTION = Regex("@([A-Za-z0-9_]{3,30})")
    }
}

@Composable
fun CommunityRepliesScreen(
    isSignedIn: Boolean,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: RepliesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val expandedSpoilers = remember { mutableStateListOf<String>() }
    var replyText by rememberSaveable { mutableStateOf("") }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    var commentEditor by remember { mutableStateOf<CommunityComment?>(null) }
    var reviewEditor by remember { mutableStateOf<MangaReview?>(null) }
    var reportTarget by remember { mutableStateOf<CommunityTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityTarget?>(null) }

    // Guest gating: rules would reject these writes; keep guests silent (H6).
    val gatedLike: (String) -> Unit = { id -> if (isSignedIn) viewModel.likeComment(id) }
    val gatedDislike: (String) -> Unit = { id -> if (isSignedIn) viewModel.dislikeComment(id) }
    val gatedMute: (String) -> Unit = { uid -> if (isSignedIn) viewModel.muteUser(uid) }
    val gatedReport: (CommunityTarget) -> Unit = { target ->
        if (isSignedIn) reportTarget = target
    }
    val gatedLikeReview: (MangaReview) -> Unit = { review -> if (isSignedIn) viewModel.likeReview(review) }
    val gatedDislikeReview: (MangaReview) -> Unit = { review -> if (isSignedIn) viewModel.dislikeReview(review) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.community_replies), color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.root?.let { root ->
                    item("thread_root") {
                        when (root) {
                            is CommunityTarget.Comment -> CommunityCommentCard(
                                comment = root.value,
                                isAuthor = state.profile?.uid == root.value.authorUid,
                                spoilerDefault = state.appSettings.spoilerCollapseDefault,
                                isSpoilerRevealed = root.value.id in expandedSpoilers,
                                onRevealSpoiler = { expandedSpoilers += root.value.id },
                                onOpenReplies = { viewModel.selectReplyTarget() },
                                onEdit = { commentEditor = root.value },
                                onDelete = { deleteTarget = root },
                                onReport = { gatedReport(root) },
                                onMute = gatedMute,
                                onProfileClick = { onOpenProfile(root.value.authorUid) },
                                onLike = { gatedLike(root.value.id) },
                                onDislike = { gatedDislike(root.value.id) }
                            )

                            is CommunityTarget.Review -> CommunityReviewCard(
                                review = root.value,
                                isAuthor = state.profile?.uid == root.value.authorUid,
                                onProfileClick = { onOpenProfile(root.value.authorUid) },
                                onOpenReplies = { viewModel.selectReplyTarget() },
                                onEdit = { reviewEditor = root.value },
                                onDelete = { deleteTarget = root },
                                onReport = { reportTarget = root },
                                onMute = { viewModel.muteUser(root.value.authorUid) },
                                onLike = { viewModel.likeReview(root.value) },
                                onDislike = { viewModel.dislikeReview(root.value) }
                            )
                        }
                    }
                }
                item("replies_header") {
                    Text(
                        stringResource(R.string.community_replies),
                        color = MangaColors.PrimaryLight,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (state.replies.isEmpty()) {
                    item("empty_replies") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.community_no_replies), color = MangaColors.Muted)
                        }
                    }
                }
                items(state.replies, key = CommunityComment::id) { reply ->
                    CommunityCommentCard(
                        comment = reply,
                        isAuthor = state.profile?.uid == reply.authorUid,
                        spoilerDefault = state.appSettings.spoilerCollapseDefault,
                        isSpoilerRevealed = reply.id in expandedSpoilers,
                        onRevealSpoiler = { expandedSpoilers += reply.id },
                        // The same comments icon retargets the flat composer instead of creating a nested reply.
                        onOpenReplies = { viewModel.selectReplyTarget() },
                        onEdit = { commentEditor = reply },
                        onDelete = { deleteTarget = CommunityTarget.Comment(reply) },
                        onReport = { gatedReport(CommunityTarget.Comment(reply)) },
                        onMute = gatedMute,
                        onProfileClick = { onOpenProfile(reply.authorUid) },
                        onLike = { gatedLike(reply.id) },
                        onDislike = { gatedDislike(reply.id) }
                    )
                }
            }
            if (isSignedIn) {
                state.replyTo?.let { recipient ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.Cyan.copy(alpha = 0.10f))
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Reply, null, tint = MangaColors.Cyan)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.community_reply_to, recipient.username.ifBlank { recipient.displayName }),
                                color = MangaColors.Cyan,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.selectReplyTarget() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = MangaColors.Muted)
                            }
                        }
                    }
                }
                CommunityComposer(
                    value = replyText,
                    spoiler = spoiler,
                    placeholder = stringResource(R.string.community_reply_hint),
                    onValueChange = { replyText = it },
                    onSpoilerChange = { spoiler = it },
                    onSend = {
                        viewModel.postReply(replyText.trim(), spoiler)
                        replyText = ""
                        spoiler = false
                    }
                )
            }
            state.error?.let { message ->
                Text(
                    message,
                    color = MangaColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    commentEditor?.let { comment ->
        CommentEditorDialog(
            initialText = comment.text,
            initialSpoiler = comment.spoiler,
            onDismiss = { commentEditor = null },
            onSave = { text, isSpoiler ->
                viewModel.updateComment(comment, text, isSpoiler)
                commentEditor = null
            }
        )
    }
    reviewEditor?.let { review ->
        ReviewEditorDialog(
            existing = review,
            onDismiss = { reviewEditor = null },
            onSave = { rating, title, body ->
                viewModel.upsertReview(rating, title, body)
                reviewEditor = null
            }
        )
    }
    reportTarget?.let { target ->
        ReportContentDialog(
            target = target,
            onDismiss = { reportTarget = null },
            onReport = { reason ->
                viewModel.report(target, reason)
                reportTarget = null
            }
        )
    }
    deleteTarget?.let { target ->
        DeleteContentDialog(
            target = target,
            onDismiss = { deleteTarget = null },
            onDelete = {
                when (target) {
                    is CommunityTarget.Comment -> viewModel.deleteComment(target.value)
                    is CommunityTarget.Review -> viewModel.deleteReview(target.value)
                }
                deleteTarget = null
            }
        )
    }
}

private fun CommunityTarget.toRecipient(): ReplyRecipient = when (this) {
    is CommunityTarget.Comment -> value.toRecipient()
    is CommunityTarget.Review -> ReplyRecipient(
        id = value.id,
        authorUid = value.authorUid,
        username = value.authorUsername,
        displayName = value.authorName
    )
}

private fun CommunityComment.toRecipient(): ReplyRecipient = ReplyRecipient(
    id = id,
    authorUid = authorUid,
    username = authorUsername,
    displayName = authorName
)

/**
 * Legacy data may contain recursively-parented comments. The UI flattens that old tree into one
 * dedicated replies feed, while new replies always point directly to the root and remain flat.
 */
private fun flattenLegacyAndFlatReplies(
    comments: List<CommunityComment>,
    rootId: String
): List<CommunityComment> {
    val childrenByParent = comments.groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<CommunityComment>()

    fun visit(parentId: String) {
        childrenByParent[parentId].orEmpty()
            .sortedBy(CommunityComment::createdAt)
            .forEach { child ->
                if (visited.add(child.id)) {
                    result += child
                    visit(child.id)
                }
            }
    }
    visit(rootId)
    return result
}
