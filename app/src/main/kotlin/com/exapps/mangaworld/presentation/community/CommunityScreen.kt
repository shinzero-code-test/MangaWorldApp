package com.exapps.mangaworld.presentation.community

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.firebase.filterMutedComments
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CommunityTab { COMMENTS, REVIEWS }

sealed interface CommunityTarget {
    val authorUid: String
    val previewText: String

    data class Comment(val value: CommunityComment) : CommunityTarget {
        override val authorUid: String = value.authorUid
        override val previewText: String = value.text
    }

    data class Review(val value: MangaReview) : CommunityTarget {
        override val authorUid: String = value.authorUid
        override val previewText: String = value.title.ifBlank { value.body }
    }
}

@Immutable
data class CommunityUiState(
    val title: String = "",
    val comments: List<CommunityComment> = emptyList(),
    val reviews: List<MangaReview> = emptyList(),
    val profile: CommunityProfile? = null,
    val appSettings: AppSettings = AppSettings(),
    val tab: CommunityTab = CommunityTab.COMMENTS,
    val chapterMode: Boolean = false,
    val focusCommentId: String? = null,
    val error: String? = null
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val communityRepository: CommunityRepository,
    private val settingsRepository: SettingsRepository,
    private val mangaCacheDao: com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
) : ViewModel() {
    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])
    private val slug: String = checkNotNull(savedStateHandle["slug"])
    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val chapterUrl: String? = decodeCommunityRouteArgument(savedStateHandle.get<String>("chapterUrl"))
    private val focusCommentId: String? = savedStateHandle.get<String>("commentId")?.takeIf { it.isNotBlank() }

    private val tab = MutableStateFlow(if (chapterUrl == null) CommunityTab.REVIEWS else CommunityTab.COMMENTS)
    private val error = MutableStateFlow<String?>(null)
    private val mangaTitle = flow {
        emit(mangaCacheDao.get(mangaId)?.title ?: slug)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, slug)
    private val commentsFlow = if (chapterUrl == null) {
        communityRepository.observeMangaComments(mangaId)
    } else {
        communityRepository.observeChapterComments(mangaId, chapterUrl)
    }
    private val reviewsFlow = if (chapterUrl == null) communityRepository.observeReviews(mangaId) else flowOf(emptyList())
    private val profileFlow: Flow<CommunityProfile?> = flow { emit(communityRepository.getCurrentProfile()) }

    val state: StateFlow<CommunityUiState> = combine(
        combine(commentsFlow, reviewsFlow, profileFlow, settingsRepository.getAppSettings()) { comments, reviews, profile, settings ->
            CommunityData(comments, reviews, profile, settings)
        },
        tab,
        error,
        mangaTitle
    ) { data, selectedTab, currentError, title ->
        val mutedUserIds = data.settings.mutedUserIds
        CommunityUiState(
            title = if (chapterUrl == null) context.getString(R.string.fmt_068, title) else context.getString(R.string.community_discussion),
            // The main screen intentionally contains roots only. Replies belong to their own stack screen.
            comments = filterMutedComments(data.comments, mutedUserIds)
                .filter { it.parentId == null && it.reviewId == null },
            reviews = data.reviews.filter { it.authorUid !in mutedUserIds },
            profile = data.profile,
            appSettings = data.settings,
            tab = selectedTab,
            chapterMode = chapterUrl != null,
            focusCommentId = focusCommentId,
            error = currentError
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        CommunityUiState(
            title = if (chapterUrl == null) context.getString(R.string.community_title) else context.getString(R.string.community_discussion),
            chapterMode = chapterUrl != null
        )
    )

    fun setTab(value: CommunityTab) {
        tab.value = value
    }

    fun postComment(text: String, spoiler: Boolean) = launchCommunityAction(R.string.community_error_post) {
        if (chapterUrl == null) {
            communityRepository.postMangaComment(mangaId, slug, sourceId, text, spoiler)
        } else {
            communityRepository.postChapterComment(mangaId, slug, sourceId, chapterUrl, text, spoiler)
        }
    }

    fun upsertReview(rating: Int, title: String, body: String) = launchCommunityAction(R.string.str_338) {
        communityRepository.upsertReview(mangaId, slug, sourceId, rating, title, body)
    }

    fun updateComment(comment: CommunityComment, text: String, spoiler: Boolean) =
        launchCommunityAction(R.string.community_error_post) {
            communityRepository.updateComment(comment, text, spoiler)
        }

    fun deleteComment(comment: CommunityComment) = launchCommunityAction(R.string.error_generic) {
        communityRepository.deleteComment(comment)
    }

    fun deleteReview(review: MangaReview) = launchCommunityAction(R.string.error_generic) {
        communityRepository.deleteReview(review)
    }

    fun report(target: CommunityTarget, reason: String) = launchCommunityAction(R.string.community_error_report) {
        when (target) {
            is CommunityTarget.Comment -> communityRepository.reportComment(target.value, reason)
            is CommunityTarget.Review -> communityRepository.reportReview(target.value, reason)
        }
    }

    fun muteUser(uid: String) = viewModelScope.launch {
        val current = settingsRepository.getAppSettings().first().mutedUserIds
        settingsRepository.setMutedUserIds(current + uid)
    }

    fun likeComment(commentId: String) = launchCommunityAction(R.string.community_error_vote) {
        communityRepository.likeComment(commentId)
    }

    fun dislikeComment(commentId: String) = launchCommunityAction(R.string.community_error_vote) {
        communityRepository.dislikeComment(commentId)
    }

    fun likeReview(review: MangaReview) = launchCommunityAction(R.string.community_error_vote) {
        communityRepository.likeReview(review.mangaId, review.id)
    }

    fun dislikeReview(review: MangaReview) = launchCommunityAction(R.string.community_error_vote) {
        communityRepository.dislikeReview(review.mangaId, review.id)
    }

    private fun launchCommunityAction(fallbackRes: Int, block: suspend () -> Unit) {
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
}

private data class CommunityData(
    val comments: List<CommunityComment>,
    val reviews: List<MangaReview>,
    val profile: CommunityProfile?,
    val settings: AppSettings
)

@Composable
fun CommunityScreen(
    isSignedIn: Boolean,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenReplies: (rootId: String, reviewId: String?, chapterUrl: String?) -> Unit,
    viewModel: CommunityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val expandedSpoilers = remember { mutableStateListOf<String>() }
    // Survive config-change/process-death so half-written comments aren't lost.
    var commentText by rememberSaveable { mutableStateOf("") }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    var commentEditor by remember { mutableStateOf<CommunityComment?>(null) }
    var reviewEditor by remember { mutableStateOf<MangaReview?>(null) }
    var showReviewEditor by rememberSaveable { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<CommunityTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityTarget?>(null) }

    // Gate interaction callbacks for guests: rules would reject the writes, so
    // guests get silent no-ops instead of error snackbars (H6).
    val gatedLike: (String) -> Unit = if (isSignedIn) viewModel::likeComment else {}
    val gatedDislike: (String) -> Unit = if (isSignedIn) viewModel::dislikeComment else {}
    val gatedMute: (String) -> Unit = if (isSignedIn) viewModel::muteUser else {}
    val gatedReport: (CommunityTarget) -> Unit = if (isSignedIn) { { reportTarget = it } } else { {} }

    // Scroll once to the focused comment; later like/vote mutations must not
    // yank scroll position back (S-review).
    var hasScrolledToFocus by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.comments, state.focusCommentId) {
        val target = state.focusCommentId ?: return@LaunchedEffect
        if (hasScrolledToFocus) return@LaunchedEffect
        val index = state.comments.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            hasScrolledToFocus = true
        }
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
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.Filled.Chat, stringResource(R.string.chat), tint = MangaColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CommunityTabs(
                selected = state.tab,
                chapterMode = state.chapterMode,
                onTabSelected = viewModel::setTab
            )
            when (state.tab) {
                CommunityTab.COMMENTS -> CommentsContent(
                    comments = state.comments,
                    listState = listState,
                    currentUserId = state.profile?.uid,
                    spoilerDefault = state.appSettings.spoilerCollapseDefault,
                    expandedSpoilers = expandedSpoilers,
                    onProfileClick = onOpenProfile,
                    onOpenReplies = { comment -> onOpenReplies(comment.id, null, comment.chapterUrl) },
                    onEdit = { commentEditor = it },
                    onDelete = { deleteTarget = CommunityTarget.Comment(it) },
                    onReport = { gatedReport(CommunityTarget.Comment(it)) },
                    onMute = gatedMute,
                    onLike = gatedLike,
                    onDislike = gatedDislike
                )

                CommunityTab.REVIEWS -> ReviewsContent(
                    reviews = state.reviews,
                    currentUserId = state.profile?.uid,
                    onAddReview = if (isSignedIn) {
                        {
                        reviewEditor = null
                        showReviewEditor = true
                        }
                    } else {
                        null
                    },
                    onProfileClick = onOpenProfile,
                    onOpenReplies = { review -> onOpenReplies(review.id, review.id, null) },
                    onEdit = {
                        reviewEditor = it
                        showReviewEditor = true
                    },
                    onDelete = { deleteTarget = CommunityTarget.Review(it) },
                    onReport = { gatedReport(CommunityTarget.Review(it)) },
                    onMute = gatedMute,
                    onLike = gatedLike,
                    onDislike = gatedDislike
                )
            }
            if (state.tab == CommunityTab.COMMENTS && isSignedIn) {
                CommunityComposer(
                    value = commentText,
                    spoiler = spoiler,
                    placeholder = stringResource(R.string.community_add_comment),
                    onValueChange = { commentText = it },
                    onSpoilerChange = { spoiler = it },
                    onSend = {
                        viewModel.postComment(commentText.trim(), spoiler)
                        commentText = ""
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
    if (showReviewEditor) {
        ReviewEditorDialog(
            existing = reviewEditor,
            onDismiss = { showReviewEditor = false },
            onSave = { rating, title, body ->
                viewModel.upsertReview(rating, title, body)
                showReviewEditor = false
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

@Composable
private fun CommunityTabs(
    selected: CommunityTab,
    chapterMode: Boolean,
    onTabSelected: (CommunityTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == CommunityTab.COMMENTS,
            onClick = { onTabSelected(CommunityTab.COMMENTS) },
            label = { Text(stringResource(R.string.comments)) },
            shape = RoundedCornerShape(12.dp)
        )
        if (!chapterMode) {
            FilterChip(
                selected = selected == CommunityTab.REVIEWS,
                onClick = { onTabSelected(CommunityTab.REVIEWS) },
                label = { Text(stringResource(R.string.community_reviews)) },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CommentsContent(
    comments: List<CommunityComment>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentUserId: String?,
    spoilerDefault: Boolean,
    expandedSpoilers: MutableList<String>,
    onProfileClick: (String) -> Unit,
    onOpenReplies: (CommunityComment) -> Unit,
    onEdit: (CommunityComment) -> Unit,
    onDelete: (CommunityComment) -> Unit,
    onReport: (CommunityComment) -> Unit,
    onMute: (String) -> Unit,
    onLike: (String) -> Unit,
    onDislike: (String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (comments.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.community_no_comments), color = MangaColors.Muted)
                }
            }
        }
        items(comments, key = CommunityComment::id) { comment ->
            CommunityCommentCard(
                comment = comment,
                isAuthor = currentUserId == comment.authorUid,
                spoilerDefault = spoilerDefault,
                isSpoilerRevealed = comment.id in expandedSpoilers,
                onRevealSpoiler = { expandedSpoilers += comment.id },
                onOpenReplies = { onOpenReplies(comment) },
                onEdit = { onEdit(comment) },
                onDelete = { onDelete(comment) },
                onReport = { onReport(comment) },
                onMute = { onMute(comment.authorUid) },
                onProfileClick = { onProfileClick(comment.authorUid) },
                onLike = { onLike(comment.id) },
                onDislike = { onDislike(comment.id) }
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewsContent(
    reviews: List<MangaReview>,
    currentUserId: String?,
    onAddReview: (() -> Unit)?,
    onProfileClick: (String) -> Unit,
    onOpenReplies: (MangaReview) -> Unit,
    onEdit: (MangaReview) -> Unit,
    onDelete: (MangaReview) -> Unit,
    onReport: (MangaReview) -> Unit,
    onMute: (String) -> Unit,
    onLike: (MangaReview) -> Unit,
    onDislike: (MangaReview) -> Unit
) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        onAddReview?.let { addReview ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = addReview),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Cyan.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, null, tint = MangaColors.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.community_add_review),
                            color = MangaColors.Cyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        if (reviews.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.community_no_reviews), color = MangaColors.Muted)
                }
            }
        }
        items(reviews, key = MangaReview::id) { review ->
            CommunityReviewCard(
                review = review,
                isAuthor = currentUserId == review.authorUid,
                onProfileClick = { onProfileClick(review.authorUid) },
                onOpenReplies = { onOpenReplies(review) },
                onEdit = { onEdit(review) },
                onDelete = { onDelete(review) },
                onReport = { onReport(review) },
                onMute = { onMute(review.authorUid) },
                onLike = { onLike(review) },
                onDislike = { onDislike(review) }
            )
        }
    }
}

internal fun decodeCommunityRouteArgument(rawValue: String?): String? = rawValue
    ?.takeIf { it.isNotBlank() }
    ?.let { encoded -> runCatching { java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(encoded) }

@Composable
internal fun CommunityCommentCard(
    comment: CommunityComment,
    isAuthor: Boolean,
    spoilerDefault: Boolean,
    isSpoilerRevealed: Boolean,
    onRevealSpoiler: () -> Unit,
    onOpenReplies: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onMute: () -> Unit,
    onProfileClick: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit
) {
    var showOverflow by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CommunityAuthorHeader(
                name = comment.authorName,
                badge = comment.authorBadge,
                avatarUrl = comment.authorAvatarUrl,
                isDeleted = comment.isDeleted,
                showOverflow = showOverflow,
                onProfileClick = onProfileClick,
                onOverflowClick = { showOverflow = true },
                onDismissOverflow = { showOverflow = false },
                isAuthor = isAuthor,
                onEdit = onEdit,
                onDelete = onDelete,
                onReport = onReport,
                onMute = onMute
            )
            if (comment.isDeleted) {
                Text(stringResource(R.string.community_deleted_content), color = MangaColors.Muted, style = MaterialTheme.typography.bodyMedium)
            } else if (comment.spoiler && spoilerDefault && !isSpoilerRevealed) {
                OutlinedButton(onClick = onRevealSpoiler, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.community_show_spoiler))
                }
            } else {
                Text(comment.text, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            CommunityReactionRow(
                likes = comment.likes,
                dislikes = comment.dislikes,
                replyCount = comment.replyCount,
                onLike = onLike,
                onDislike = onDislike,
                onOpenReplies = onOpenReplies
            )
        }
    }
}

@Composable
internal fun CommunityReviewCard(
    review: MangaReview,
    isAuthor: Boolean,
    onProfileClick: () -> Unit,
    onOpenReplies: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onMute: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit
) {
    var showOverflow by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CommunityAuthorHeader(
                name = review.authorName,
                badge = review.authorBadge,
                avatarUrl = review.authorAvatarUrl,
                isDeleted = review.isDeleted,
                showOverflow = showOverflow,
                onProfileClick = onProfileClick,
                onOverflowClick = { showOverflow = true },
                onDismissOverflow = { showOverflow = false },
                isAuthor = isAuthor,
                onEdit = onEdit,
                onDelete = onDelete,
                onReport = onReport,
                onMute = onMute,
                leadingBadge = {
                    Text(
                        stringResource(R.string.community_rating_format, review.rating),
                        color = MangaColors.Yellow,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
            if (review.isDeleted) {
                Text(stringResource(R.string.community_deleted_content), color = MangaColors.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                if (review.title.isNotBlank()) {
                    Text(review.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                }
                if (review.body.isNotBlank()) {
                    Text(review.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            CommunityReactionRow(
                likes = review.likes,
                dislikes = review.dislikes,
                replyCount = review.replyCount,
                onLike = onLike,
                onDislike = onDislike,
                onOpenReplies = onOpenReplies
            )
        }
    }
}

@Composable
private fun CommunityAuthorHeader(
    name: String,
    badge: String,
    avatarUrl: String?,
    isDeleted: Boolean,
    showOverflow: Boolean,
    onProfileClick: () -> Unit,
    onOverflowClick: () -> Unit,
    onDismissOverflow: () -> Unit,
    isAuthor: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onMute: () -> Unit,
    leadingBadge: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommunityAvatar(name, avatarUrl)
            Column(modifier = Modifier.clickable(enabled = !isDeleted, onClick = onProfileClick)) {
                Text(name, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    leadingBadge?.invoke()
                    Text(badge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!isDeleted) {
            Box {
                IconButton(onClick = onOverflowClick, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.options), tint = MangaColors.Muted)
                }
                DropdownMenu(expanded = showOverflow, onDismissRequest = onDismissOverflow) {
                    if (isAuthor) {
                        Text(
                            stringResource(R.string.edit),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable {
                                onEdit()
                                onDismissOverflow()
                            }
                        )
                        Text(
                            stringResource(R.string.delete),
                            color = MangaColors.Error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable {
                                onDelete()
                                onDismissOverflow()
                            }
                        )
                    } else {
                        Text(
                            stringResource(R.string.community_report),
                            color = MangaColors.Yellow,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable {
                                onReport()
                                onDismissOverflow()
                            }
                        )
                        Text(
                            stringResource(R.string.community_mute),
                            color = MangaColors.Muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable {
                                onMute()
                                onDismissOverflow()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityAvatar(name: String, avatarUrl: String?) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MangaColors.Primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1), color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CommunityReactionRow(
    likes: Int,
    dislikes: Int,
    replyCount: Int,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onOpenReplies: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLike, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ThumbUp, stringResource(R.string.community_like), tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
        }
        Text(likes.toString(), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onDislike, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ThumbDown, stringResource(R.string.community_dislike), tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
        }
        Text(dislikes.toString(), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onOpenReplies, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Forum, stringResource(R.string.community_view_replies), tint = MangaColors.PrimaryLight, modifier = Modifier.size(18.dp))
        }
        if (replyCount > 0) {
            Text(stringResource(R.string.fmt_025, replyCount), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun CommunityComposer(
    value: String,
    spoiler: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSpoilerChange: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(10.dp),
                maxLines = 4
            )
            IconButton(onClick = { onSpoilerChange(!spoiler) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (spoiler) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    stringResource(R.string.community_spoiler),
                    tint = if (spoiler) MangaColors.Yellow else MangaColors.Muted
                )
            }
            IconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Send, stringResource(R.string.community_send), tint = MangaColors.Cyan)
            }
        }
    }
}

@Composable
internal fun CommentEditorDialog(
    initialText: String,
    initialSpoiler: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var spoiler by remember(initialSpoiler) { mutableStateOf(initialSpoiler) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = { Text(stringResource(R.string.community_edit_comment), color = MangaColors.OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.community_add_comment)) },
                    minLines = 3,
                    shape = RoundedCornerShape(10.dp)
                )
                FilterChip(
                    selected = spoiler,
                    onClick = { spoiler = !spoiler },
                    label = { Text(stringResource(R.string.community_spoiler)) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text.trim(), spoiler) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun ReviewEditorDialog(
    existing: MangaReview?,
    onDismiss: () -> Unit,
    onSave: (Int, String, String) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing?.id) { mutableStateOf(existing?.body.orEmpty()) }
    var rating by remember(existing?.id) { mutableStateOf(existing?.rating ?: 5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = {
            Text(
                stringResource(if (existing == null) R.string.community_add_review else R.string.community_edit_review),
                color = MangaColors.OnSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.community_review_title_hint)) },
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.review_details)) },
                    minLines = 4,
                    shape = RoundedCornerShape(10.dp)
                )
                Text(stringResource(R.string.rating), color = MangaColors.OnSurface, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { star ->
                        FilterChip(
                            selected = rating == star,
                            onClick = { rating = star },
                            label = { Text(star.toString()) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(rating, title.trim(), body.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun ReportContentDialog(
    target: CommunityTarget,
    onDismiss: () -> Unit,
    onReport: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = { Text(stringResource(R.string.community_report_title), color = MangaColors.OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.previewText, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.community_report_reason)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onReport(reason.trim()) },
                enabled = reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)
            ) { Text(stringResource(R.string.community_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun DeleteContentDialog(
    target: CommunityTarget,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val title = when (target) {
        is CommunityTarget.Comment -> stringResource(R.string.community_delete_comment)
        is CommunityTarget.Review -> stringResource(R.string.community_delete_review)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = { Text(title, color = MangaColors.OnSurface) },
        text = { Text(stringResource(R.string.community_delete_content_confirm), color = MangaColors.OnSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MangaColors.Error)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
