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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.exapps.mangaworld.presentation.components.GlassCard
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    val error: String? = null,
    /** A comment send is in flight — composer locks + shows progress. */
    val isSending: Boolean = false,
    /** Last successful comment send (ms epoch) — composer clears on change. */
    val lastCommentSentAt: Long? = null,
    /** This client's vote per target id (1 / -1) — drives button highlight. */
    val myVotes: Map<String, Int> = emptyMap(),
    /** Targets with a vote request in flight — their buttons lock. */
    val votesInFlight: Set<String> = emptySet()
)

/** Local overlay of a mutation until the authoritative Firestore snapshot lands (v8 #6). */
@Immutable
data class PendingEdit(val text: String, val spoiler: Boolean, val at: Long)

/**
 * Optimistic vote overlay until the authoritative snapshot lands (Issue 2).
 * Client mirror of the server toggle (`vote-transition.ts`): repeat retracts,
 * opposite switches, fresh adds. Counts render as server + delta so later
 * snapshot movement (other voters) stays correct under the overlay; the echo
 * evicts once the snapshot carries the expected counts or the TTL lapses.
 */
@Immutable
internal data class VoteEcho(
    val targetId: String,
    /** 1 / -1 / null(retracted). Drives the button highlight. */
    val myVote: Int?,
    val baseLikes: Int,
    val baseDislikes: Int,
    val expectedLikes: Int,
    val expectedDislikes: Int,
    val at: Long
) {
    val dLikes: Int get() = expectedLikes - baseLikes
    val dDislikes: Int get() = expectedDislikes - baseDislikes
}

/** Vote tap debounce + post-vote snapshot-confirm windows. */
internal const val VOTE_TAP_DEBOUNCE_MS = 300L
internal const val VOTE_CONFIRM_DELAY_MS = 2_000L

/**
 * Pure client-side vote transition. [baseLikes]/[baseDislikes] must come from
 * the chained echo when one exists, else the server snapshot — never a stale
 * mix, or chained taps drift off by one.
 */
internal fun computeVoteEcho(
    targetId: String,
    tappedVote: Int,
    previousMyVote: Int?,
    baseLikes: Int,
    baseDislikes: Int,
    at: Long = System.currentTimeMillis()
): VoteEcho {
    require(tappedVote == 1 || tappedVote == -1)
    if (previousMyVote == tappedVote) {
        return if (tappedVote == 1) {
            VoteEcho(targetId, null, baseLikes, baseDislikes, maxOf(0, baseLikes - 1), baseDislikes, at)
        } else {
            VoteEcho(targetId, null, baseLikes, baseDislikes, baseLikes, maxOf(0, baseDislikes - 1), at)
        }
    }
    var likes = baseLikes
    var dislikes = baseDislikes
    if (previousMyVote == 1) likes = maxOf(0, likes - 1)
    if (previousMyVote == -1) dislikes = maxOf(0, dislikes - 1)
    if (tappedVote == 1) likes += 1 else dislikes += 1
    return VoteEcho(targetId, tappedVote, baseLikes, baseDislikes, likes, dislikes, at)
}

@Immutable
data class PendingReviewEdit(val title: String, val body: String, val rating: Int, val at: Long)

@Immutable
private data class PendingUi(
    val commentEchoes: List<CommunityComment> = emptyList(),
    val reviewEchoes: List<MangaReview> = emptyList(),
    val edits: Map<String, PendingEdit> = emptyMap(),
    val reviewEdits: Map<String, PendingReviewEdit> = emptyMap(),
    val sendingComments: Int = 0,
    val lastCommentSentAt: Long? = null,
    /** Optimistic vote overlays by target id (comment or review id). */
    val voteEchoes: Map<String, VoteEcho> = emptyMap(),
    /** This client's vote by target id (1 / -1); absent = none. Survives snapshots. */
    val myVotes: Map<String, Int> = emptyMap(),
    /** Targets with a vote request in flight — buttons lock (rec-3). */
    val voteInFlight: Set<String> = emptySet(),
    /** Last accepted tap per target (ms epoch) — 300ms accidental-double-tap guard. */
    val lastVoteTapMs: Map<String, Long> = emptyMap()
)

@Immutable
private data class UiBits(
    val tab: CommunityTab,
    val error: String?,
    val title: String,
    val pending: PendingUi
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
    private val pending = MutableStateFlow(PendingUi())
    // Freeze guard: every upstream below is failure-isolated. A throwing or
    // never-emitting source used to kill/starve the combine, leaving the
    // screen on its initial state forever — bare community_title, dead tabs,
    // empty lists. Now each source falls back and the error banner explains.
    private val mangaTitle = flow {
        emit(runCatching { mangaCacheDao.get(mangaId)?.title ?: slug }.getOrDefault(slug))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, slug)
    private val commentsFlow: Flow<List<CommunityComment>> = (if (chapterUrl == null) {
        communityRepository.observeMangaComments(mangaId)
    } else {
        communityRepository.observeChapterComments(mangaId, chapterUrl)
    }).catch {
        error.value = context.getString(R.string.community_error_generic_action)
        emit(emptyList())
    }
    private val reviewsFlow: Flow<List<MangaReview>> =
        (if (chapterUrl == null) communityRepository.observeReviews(mangaId) else flowOf(emptyList()))
            .catch {
                error.value = context.getString(R.string.community_error_generic_action)
                emit(emptyList())
            }
    private val profileFlow: Flow<CommunityProfile?> = flow {
        emit(runCatching { communityRepository.getCurrentProfile() }.getOrNull())
    }

    val state: StateFlow<CommunityUiState> = combine(
        combine(commentsFlow, reviewsFlow, profileFlow, settingsRepository.getAppSettings()) { comments, reviews, profile, settings ->
            CommunityData(comments, reviews, profile, settings)
        },
        combine(tab, error, mangaTitle, pending) { selectedTab, currentError, title, pd ->
            UiBits(selectedTab, currentError, title, pd)
        }
    ) { data, bits ->
        val mutedUserIds = data.settings.mutedUserIds
        val now = System.currentTimeMillis()
        // Optimistic overlays (v8 #6): echoes vanish once the snapshot carries the
        // real item; pending edits yield as soon as editedAt reflects the change.
        // Vote echoes (Issue 2): counters render as server + delta while the
        // echo is fresh and the snapshot hasn't landed the expected counts.
        val voteEchoes = bits.pending.voteEchoes
        fun applyVoteEcho(id: String, likes: Int, dislikes: Int): Pair<Int, Int> {
            val echo = voteEchoes[id]
                ?.takeIf { now - it.at < ECHO_TTL_MS && (likes != it.expectedLikes || dislikes != it.expectedDislikes) }
                ?: return likes to dislikes
            return maxOf(0, likes + echo.dLikes) to maxOf(0, dislikes + echo.dDislikes)
        }
        val liveComments = data.comments.map { c ->
            val edited = bits.pending.edits[c.id]
                ?.takeIf { (c.editedAt ?: 0L) < it.at - EDIT_LANDED_TOLERANCE_MS }
                ?.let { c.copy(text = it.text, spoiler = it.spoiler) }
                ?: c
            val (likes, dislikes) = applyVoteEcho(c.id, edited.likes, edited.dislikes)
            edited.copy(likes = likes, dislikes = dislikes)
        }
        val echoedComments = bits.pending.commentEchoes.filter { echo ->
            now - echo.createdAt < ECHO_TTL_MS &&
                data.comments.none { it.authorUid == echo.authorUid && it.text == echo.text && !it.isDeleted }
        }
        val editedReviews = data.reviews.map { r ->
            val edited = bits.pending.reviewEdits[r.id]
                ?.takeIf { r.updatedAt < it.at - EDIT_LANDED_TOLERANCE_MS }
                ?.let { r.copy(title = it.title, body = it.body, rating = it.rating) }
                ?: r
            val (likes, dislikes) = applyVoteEcho(r.id, edited.likes, edited.dislikes)
            edited.copy(likes = likes, dislikes = dislikes)
        }
        val echoedReviews = bits.pending.reviewEchoes.filter { echo ->
            now - echo.updatedAt < ECHO_TTL_MS &&
                data.reviews.none { it.authorUid == echo.authorUid && it.title == echo.title && !it.isDeleted }
        }
        // Deduplicate reviews by ID: editedReviews (server + pending edits) takes priority
        // over echoedReviews (optimistic new reviews). This prevents duplicate keys when
        // a review is edited and the optimistic echo still exists locally.
        val allReviews = (editedReviews + echoedReviews)
            .filter { it.authorUid !in mutedUserIds }
            .distinctBy { it.id }
        CommunityUiState(
            title = if (chapterUrl == null) context.getString(R.string.fmt_068, bits.title) else context.getString(R.string.community_discussion),
            // The main screen intentionally contains roots only. Replies belong to their own stack screen.
            comments = (filterMutedComments(liveComments, mutedUserIds) + echoedComments)
                .filter { it.parentId == null && it.reviewId == null },
            reviews = allReviews,
            profile = data.profile,
            appSettings = data.settings,
            tab = bits.tab,
            chapterMode = chapterUrl != null,
            focusCommentId = focusCommentId,
            error = bits.error,
            isSending = bits.pending.sendingComments > 0,
            lastCommentSentAt = bits.pending.lastCommentSentAt,
            myVotes = bits.pending.myVotes,
            votesInFlight = bits.pending.voteInFlight
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

    /** Resolves an @mention to a uid for profile navigation. Null when unknown/offline. */
    suspend fun resolveMention(username: String): String? =
        runCatching { communityRepository.getUidForUsername(username) }.getOrNull()

    fun postComment(text: String, spoiler: Boolean) {
        val profile = state.value.profile
        // No silent skip: a missing profile (session still resolving) used to
        // swallow the tap while the composer cleared itself — the "vanished
        // comment" report. Surface it instead.
        if (profile == null || text.isBlank()) {
            if (profile == null) error.value = context.getString(R.string.community_error_sign_in)
            return
        }
        var echo: CommunityComment? = null
        launchCommunityAction(R.string.community_error_post, trackSending = true) {
            echo = CommunityComment(
                id = "pending_${java.util.UUID.randomUUID()}",
                mangaId = mangaId,
                chapterUrl = chapterUrl,
                slug = slug,
                sourceId = sourceId,
                authorUid = profile.uid,
                authorName = profile.displayName.ifBlank { profile.username },
                authorUsername = profile.username,
                authorAvatarUrl = profile.avatarUrl,
                text = text.trim(),
                spoiler = spoiler,
                createdAt = System.currentTimeMillis()
            )
            pending.update { it.copy(commentEchoes = it.commentEchoes + echo!!) }
            try {
                if (chapterUrl == null) {
                    communityRepository.postMangaComment(mangaId, slug, sourceId, text, spoiler)
                } else {
                    communityRepository.postChapterComment(mangaId, slug, sourceId, chapterUrl, text, spoiler)
                }
                // Clear-on-success signal — the composer wipes its draft only
                // once the write actually landed (see LaunchedEffect below).
                pending.update { it.copy(lastCommentSentAt = System.currentTimeMillis()) }
            } catch (t: Throwable) {
                echo?.let { e -> pending.update { it.copy(commentEchoes = it.commentEchoes - e) } }
                throw t
            }
        }
    }

    fun upsertReview(rating: Int, title: String, body: String) {
        val profile = state.value.profile
        // One review per user per manga: an existing (non-deleted) review is
        // EDITED in place (same doc id = uid), never duplicated.
        val existing = state.value.reviews.firstOrNull { it.authorUid == profile?.uid && !it.isDeleted }
        var reviewEcho: MangaReview? = null
        launchCommunityAction(R.string.str_338) {
            if (profile != null) {
                val at = System.currentTimeMillis()
                if (existing != null) {
                    // Edit path: overlay via reviewEdits so the new content
                    // renders IMMEDIATELY. (Echoes are shadowed by distinctBy
                    // and only appear after the snapshot lands — the reported
                    // "edit invisible until tab switch" bug.)
                    pending.update {
                        it.copy(
                            reviewEdits = it.reviewEdits + (existing.id to PendingReviewEdit(title.trim(), body.trim(), rating.coerceIn(1, 5), at))
                        )
                    }
                } else {
                    reviewEcho = MangaReview(
                        id = "pending_review_${java.util.UUID.randomUUID()}",
                        mangaId = mangaId,
                        authorUid = profile.uid,
                        authorName = profile.displayName.ifBlank { profile.username },
                        authorUsername = profile.username,
                        authorAvatarUrl = profile.avatarUrl,
                        rating = rating.coerceIn(1, 5),
                        title = title.trim(),
                        body = body.trim(),
                        updatedAt = at,
                        isDeleted = false
                    )
                    pending.update {
                        it.copy(
                            reviewEchoes = it.reviewEchoes.filterNot { e -> e.authorUid == profile.uid } + reviewEcho!!
                        )
                    }
                }
            }
            try {
                communityRepository.upsertReview(mangaId, slug, sourceId, rating, title, body)
            } catch (t: Throwable) {
                if (existing != null) {
                    pending.update { s -> s.copy(reviewEdits = s.reviewEdits - existing.id) }
                }
                reviewEcho?.let { e -> pending.update { s -> s.copy(reviewEchoes = s.reviewEchoes - e) } }
                throw t
            }
        }
    }

    fun updateComment(comment: CommunityComment, text: String, spoiler: Boolean) =
        launchCommunityAction(R.string.community_error_post) {
            val at = System.currentTimeMillis()
            pending.update { it.copy(edits = it.edits + (comment.id to PendingEdit(text.trim(), spoiler, at))) }
            try {
                communityRepository.updateComment(comment, text, spoiler)
            } catch (t: Throwable) {
                pending.update { it.copy(edits = it.edits - comment.id) }
                throw t
            }
        }

    fun deleteComment(comment: CommunityComment) = launchCommunityAction(R.string.community_error_generic_action) {
        communityRepository.deleteComment(comment)
        // Instant local removal; the snapshot confirms either way.
        pending.update { it.copy(commentEchoes = it.commentEchoes.filterNot { e -> e.id == comment.id }) }
    }

    fun deleteReview(review: MangaReview) = launchCommunityAction(R.string.community_error_generic_action) {
        communityRepository.deleteReview(review)
        pending.update { s ->
            s.copy(
                reviewEchoes = s.reviewEchoes.filterNot { e -> e.id == review.id },
                reviewEdits = s.reviewEdits - review.id
            )
        }
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

    fun likeComment(commentId: String) = castCommentVote(commentId, 1)

    fun dislikeComment(commentId: String) = castCommentVote(commentId, -1)

    fun likeReview(review: MangaReview) = castReviewVote(review, 1)

    fun dislikeReview(review: MangaReview) = castReviewVote(review, -1)

    /**
     * Optimistic vote pipeline (Issue 2): instant echo + highlight on tap,
     * in-flight lock + 300ms dedupe against blind-toggle flip-flop (rec-3),
     * authoritative reconcile on response (rec-2), 2s snapshot-confirm with a
     * targeted one-shot re-fetch for stalled Watch streams (rec-4).
     */
    private fun castCommentVote(commentId: String, tappedVote: Int) {
        val now = System.currentTimeMillis()
        val snapshot = pending.value
        if (commentId in snapshot.voteInFlight) return
        if (now - (snapshot.lastVoteTapMs[commentId] ?: 0L) < VOTE_TAP_DEBOUNCE_MS) return
        val server = state.value.comments.firstOrNull { it.id == commentId } ?: return
        castVote(
            targetId = commentId,
            tappedVote = tappedVote,
            serverCounts = server.likes to server.dislikes,
            priorMyVote = snapshot.myVotes[commentId],
            post = { v -> if (v == 1) communityRepository.likeComment(commentId) else communityRepository.dislikeComment(commentId) },
            serverCountsNow = {
                state.value.comments.firstOrNull { it.id == commentId }?.let { it.likes to it.dislikes }
            },
            fetch = { communityRepository.fetchCommentVote(commentId)?.let { it.likes to it.dislikes } }
        )
    }

    private fun castReviewVote(review: MangaReview, tappedVote: Int) {
        val now = System.currentTimeMillis()
        val snapshot = pending.value
        if (review.id in snapshot.voteInFlight) return
        if (now - (snapshot.lastVoteTapMs[review.id] ?: 0L) < VOTE_TAP_DEBOUNCE_MS) return
        castVote(
            targetId = review.id,
            tappedVote = tappedVote,
            serverCounts = review.likes to review.dislikes,
            priorMyVote = snapshot.myVotes[review.id],
            post = { v -> if (v == 1) communityRepository.likeReview(review.mangaId, review.id) else communityRepository.dislikeReview(review.mangaId, review.id) },
            serverCountsNow = {
                state.value.reviews.firstOrNull { it.id == review.id }?.let { it.likes to it.dislikes }
            },
            fetch = { communityRepository.fetchReviewVote(review.mangaId, review.id)?.let { it.likes to it.dislikes } }
        )
    }

    private fun castVote(
        targetId: String,
        tappedVote: Int,
        serverCounts: Pair<Int, Int>,
        priorMyVote: Int?,
        post: suspend (Int) -> com.exapps.mangaworld.domain.model.VoteOutcome,
        serverCountsNow: () -> Pair<Int, Int>?,
        fetch: suspend () -> Pair<Int, Int>?
    ) {
        val now = System.currentTimeMillis()
        val chained = pending.value.voteEchoes[targetId]
        val echo = computeVoteEcho(
            targetId = targetId,
            tappedVote = tappedVote,
            previousMyVote = chained?.myVote ?: priorMyVote,
            baseLikes = chained?.expectedLikes ?: serverCounts.first,
            baseDislikes = chained?.expectedDislikes ?: serverCounts.second,
            at = now
        )
        pending.update {
            it.copy(
                voteEchoes = it.voteEchoes + (targetId to echo),
                myVotes = if (echo.myVote == null) it.myVotes - targetId else it.myVotes + (targetId to echo.myVote),
                voteInFlight = it.voteInFlight + targetId,
                lastVoteTapMs = it.lastVoteTapMs + (targetId to now)
            )
        }
        launchCommunityAction(R.string.community_error_vote) {
            val outcome = try {
                post(tappedVote)
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                // Failed write must not leave a lying echo/highlight behind.
                pending.update {
                    it.copy(
                        voteEchoes = it.voteEchoes - targetId,
                        myVotes = if (priorMyVote == null) it.myVotes - targetId else it.myVotes + (targetId to priorMyVote),
                        voteInFlight = it.voteInFlight - targetId
                    )
                }
                throw throwable
            }
            // Authoritative reconcile: the response carries the true counts.
            pending.update {
                it.copy(
                    voteEchoes = it.voteEchoes + (targetId to echo.copy(
                        myVote = outcome.myVote,
                        expectedLikes = outcome.likes,
                        expectedDislikes = outcome.dislikes,
                        at = System.currentTimeMillis()
                    )),
                    myVotes = if (outcome.myVote == null) it.myVotes - targetId else it.myVotes + (targetId to outcome.myVote),
                    voteInFlight = it.voteInFlight - targetId
                )
            }
            confirmVoteEcho(targetId, serverCountsNow, fetch)
        }
    }

    /**
     * Belt-and-braces reconcile (rec-4): 2s after the write, the snapshot
     * should carry the expected counts. If it does, evict early. If not,
     * one-shot-fetch the doc: a match means the Watch stream is just slow
     * (extend the echo), anything else (or an unreadable doc) evicts and lets
     * server truth rule. Never throws.
     */
    private suspend fun confirmVoteEcho(
        targetId: String,
        serverCountsNow: () -> Pair<Int, Int>?,
        fetch: suspend () -> Pair<Int, Int>?
    ) {
        kotlinx.coroutines.delay(VOTE_CONFIRM_DELAY_MS)
        if (targetId !in pending.value.voteEchoes) return
        val live = serverCountsNow()
        val current = pending.value.voteEchoes[targetId]
        if (current != null && live != null
            && live.first == current.expectedLikes && live.second == current.expectedDislikes
        ) {
            pending.update { it.copy(voteEchoes = it.voteEchoes - targetId) }
            return
        }
        if (current == null) return
        val fresh = runCatching { fetch() }.getOrNull() ?: return
        pending.update { cur ->
            val echo = cur.voteEchoes[targetId] ?: return@update cur
            if (fresh.first == echo.expectedLikes && fresh.second == echo.expectedDislikes) {
                cur.copy(voteEchoes = cur.voteEchoes + (targetId to echo.copy(at = System.currentTimeMillis())))
            } else {
                cur.copy(voteEchoes = cur.voteEchoes - targetId)
            }
        }
    }

    fun dismissError() {
        error.value = null
    }

    private companion object {
        /** Optimistic echo lifetime before the snapshot is assumed authoritative. */
        const val ECHO_TTL_MS = 10_000L
        /** editedAt within this window of the local write counts as "landed". */
        const val EDIT_LANDED_TOLERANCE_MS = 2_000L
    }

    private fun launchCommunityAction(fallbackRes: Int, trackSending: Boolean = false, block: suspend () -> Unit) {
        // NonCancellable: navigation away (or process-lifecycle churn) must
        // not abort an in-flight community write the UI already confirmed.
        // The repository layer is NonCancellable too — this covers the VM
        // overlay bookkeeping around it.
        viewModelScope.launch(NonCancellable) {
            if (trackSending) pending.update { it.copy(sendingComments = it.sendingComments + 1) }
            try {
                block()
                error.value = null
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                error.value = context.getString(fallbackRes)
            } finally {
                if (trackSending) pending.update { it.copy(sendingComments = (it.sendingComments - 1).coerceAtLeast(0)) }
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
    // guests get a sign-in prompt snackbar instead of silent no-ops (A-18).
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val guestPrompt = stringResource(R.string.community_error_sign_in)
    fun promptGuest() { scope.launch { snackbar.showSnackbar(guestPrompt) } }
    val gatedLike: (String) -> Unit = { id -> if (isSignedIn) viewModel.likeComment(id) else promptGuest() }
    val gatedDislike: (String) -> Unit = { id -> if (isSignedIn) viewModel.dislikeComment(id) else promptGuest() }
    val gatedMute: (String) -> Unit = { uid -> if (isSignedIn) viewModel.muteUser(uid) else promptGuest() }
    val gatedLikeReview: (MangaReview) -> Unit = { review -> if (isSignedIn) viewModel.likeReview(review) else promptGuest() }
    val gatedDislikeReview: (MangaReview) -> Unit = { review -> if (isSignedIn) viewModel.dislikeReview(review) else promptGuest() }
    val gatedReport: (CommunityTarget) -> Unit = { target ->
        if (isSignedIn) reportTarget = target else promptGuest()
    }
    val unknownUserPrompt = stringResource(R.string.community_mention_unknown)
    val gatedMention: (String) -> Unit = { name ->
        if (isSignedIn) {
            scope.launch {
                val uid = viewModel.resolveMention(name)
                if (uid != null) onOpenProfile(uid)
                else snackbar.showSnackbar(unknownUserPrompt)
            }
        } else promptGuest()
    }

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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                // v8 (#4): the unlabeled chat icon sat in the RTL "top-left" slot and
                // was constantly mistaken for a second comments button. Chat moved to
                // a labeled chip in the tab row below.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CommunityTabs(
                selected = state.tab,
                chapterMode = state.chapterMode,
                onTabSelected = viewModel::setTab,
                onOpenChat = onOpenChat
            )
            when (state.tab) {
                CommunityTab.COMMENTS -> CommentsContent(
                    comments = state.comments,
                    listState = listState,
                    currentUserId = state.profile?.uid,
                    spoilerDefault = state.appSettings.spoilerCollapseDefault,
                    expandedSpoilers = expandedSpoilers,
                    myVotes = state.myVotes,
                    votesInFlight = state.votesInFlight,
                    onProfileClick = onOpenProfile,
                    onOpenReplies = { comment -> onOpenReplies(comment.id, null, comment.chapterUrl) },
                    onEdit = { commentEditor = it },
                    onDelete = { deleteTarget = CommunityTarget.Comment(it) },
                    onReport = { gatedReport(CommunityTarget.Comment(it)) },
                    onMute = gatedMute,
                    onLike = gatedLike,
                    onDislike = gatedDislike,
                    onMentionClick = gatedMention
                )

                CommunityTab.REVIEWS -> ReviewsContent(
                    reviews = state.reviews,
                    currentUserId = state.profile?.uid,
                    myVotes = state.myVotes,
                    votesInFlight = state.votesInFlight,                    onAddReview = if (isSignedIn) {
                        {
                        reviewEditor = null
                        showReviewEditor = true
                        }
                    } else {
                        // A-18: guests get the sign-in prompt instead of a
                        // missing affordance.
                        { promptGuest() }
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
                    onLike = gatedLikeReview,
                    onDislike = gatedDislikeReview,
                    onMentionClick = gatedMention
                )
            }
            if (state.tab == CommunityTab.COMMENTS && isSignedIn) {
                // Clear-on-success: the draft survives until the write lands,
                // so a failure keeps the text for retry instead of eating it.
                androidx.compose.runtime.LaunchedEffect(state.lastCommentSentAt) {
                    if (state.lastCommentSentAt != null) {
                        commentText = ""
                        spoiler = false
                    }
                }
                CommunityComposer(
                    value = commentText,
                    spoiler = spoiler,
                    placeholder = stringResource(R.string.community_add_comment),
                    onValueChange = { commentText = it },
                    onSpoilerChange = { spoiler = it },
                    onSend = { viewModel.postComment(commentText.trim(), spoiler) },
                    sending = state.isSending
                )
            } else if (state.tab == CommunityTab.COMMENTS) {
                // A-18: guests see why there is no composer (chat parity).
                Text(
                    stringResource(R.string.reader_sign_in_to_participate),
                    color = MangaColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            state.error?.let { message ->
                // Prominent action banner — the old bottom-aligned caption was
                // invisible in practice, which read as "nothing happened" (v8 #6).
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    glowColors = listOf(MangaColors.Error, MangaColors.Error)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Close, null, tint = MangaColors.Error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            message,
                            color = MangaColors.Error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::dismissError) {
                            Text(stringResource(R.string.dismiss), color = MangaColors.MutedLight)
                        }
                    }
                }
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
    onTabSelected: (CommunityTab) -> Unit,
    onOpenChat: () -> Unit
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
        // Labeled chat entry — text label prevents the old "duplicate comments
        // button" confusion (v8 #4).
        FilterChip(
            selected = false,
            onClick = onOpenChat,
            label = { Text(stringResource(R.string.live_chat), color = MangaColors.Cyan) },
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(Icons.Filled.Chat, stringResource(R.string.chat), tint = MangaColors.Cyan, modifier = Modifier.size(16.dp))
            }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CommentsContent(
    comments: List<CommunityComment>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentUserId: String?,
    spoilerDefault: Boolean,
    expandedSpoilers: MutableList<String>,
    myVotes: Map<String, Int> = emptyMap(),
    votesInFlight: Set<String> = emptySet(),
    onProfileClick: (String) -> Unit,
    onOpenReplies: (CommunityComment) -> Unit,
    onEdit: (CommunityComment) -> Unit,
    onDelete: (CommunityComment) -> Unit,
    onReport: (CommunityComment) -> Unit,
    onMute: (String) -> Unit,
    onLike: (String) -> Unit,
    onDislike: (String) -> Unit,
    onMentionClick: ((String) -> Unit)? = null
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
                onDislike = { onDislike(comment.id) },
                myVote = myVotes[comment.id],
                votesEnabled = comment.id !in votesInFlight,
                onMentionClick = onMentionClick
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
    onDislike: (MangaReview) -> Unit,
    myVotes: Map<String, Int> = emptyMap(),
    votesInFlight: Set<String> = emptySet(),
    onMentionClick: ((String) -> Unit)? = null
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
                onDislike = { onDislike(review) },
                myVote = myVotes[review.id],
                votesEnabled = review.id !in votesInFlight,
                onMentionClick = onMentionClick
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
    onDislike: () -> Unit,
    myVote: Int? = null,
    votesEnabled: Boolean = true,
    onMentionClick: ((String) -> Unit)? = null
) {
    var showOverflow by remember { mutableStateOf(false) }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowColors = if (isAuthor) MangaColors.GradientPurpleCyan
                     else listOf(MangaColors.OutlineVariant, MangaColors.OutlineVariant)
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
                MentionText(
                    text = comment.text,
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    onMentionClick = onMentionClick
                )
            }
            CommunityReactionRow(
                likes = comment.likes,
                dislikes = comment.dislikes,
                replyCount = comment.replyCount,
                myVote = myVote,
                votesEnabled = votesEnabled,
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
    onDislike: () -> Unit,
    myVote: Int? = null,
    votesEnabled: Boolean = true,
    onMentionClick: ((String) -> Unit)? = null
) {
    var showOverflow by remember { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                    MentionText(
                        review.title,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        onMentionClick = onMentionClick
                    )
                }
                if (review.body.isNotBlank()) {
                    MentionText(
                        review.body,
                        color = MangaColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        onMentionClick = onMentionClick
                    )
                }
            }
            CommunityReactionRow(
                likes = review.likes,
                dislikes = review.dislikes,
                replyCount = review.replyCount,
                myVote = myVote,
                votesEnabled = votesEnabled,
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
            // Avatar opens the profile too — previously only the name did.
            Box(modifier = Modifier.clickable(enabled = !isDeleted, onClick = onProfileClick)) {
                CommunityAvatar(name, avatarUrl)
            }
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
    myVote: Int? = null,
    votesEnabled: Boolean = true,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onOpenReplies: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLike, enabled = votesEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ThumbUp, stringResource(R.string.community_like), tint = if (myVote == 1) MangaColors.Cyan else MangaColors.Muted, modifier = Modifier.size(18.dp))
        }
        Text(likes.toString(), color = if (myVote == 1) MangaColors.Cyan else MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onDislike, enabled = votesEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ThumbDown, stringResource(R.string.community_dislike), tint = if (myVote == -1) MangaColors.Cyan else MangaColors.Muted, modifier = Modifier.size(18.dp))
        }
        Text(dislikes.toString(), color = if (myVote == -1) MangaColors.Cyan else MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
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
    onSend: () -> Unit,
    sending: Boolean = false
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
                maxLines = 4,
                enabled = !sending
            )
            IconButton(onClick = { onSpoilerChange(!spoiler) }, modifier = Modifier.size(48.dp), enabled = !sending) {
                Icon(
                    if (spoiler) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    stringResource(R.string.community_spoiler),
                    tint = if (spoiler) MangaColors.Yellow else MangaColors.Muted
                )
            }
            if (sending) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(48.dp).padding(12.dp),
                    color = MangaColors.Cyan,
                    strokeWidth = 3.dp
                )
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Send, stringResource(R.string.community_send), tint = MangaColors.Cyan)
                }
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
