package com.exapps.mangaworld.presentation.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CommunityTab { COMMENTS, REVIEWS }

data class CommunityUiState(
    val title: String = "المجتمع",
    val comments: List<CommunityComment> = emptyList(),
    val reviews: List<MangaReview> = emptyList(),
    val profile: CommunityProfile? = null,
    val tab: CommunityTab = CommunityTab.COMMENTS,
    val chapterMode: Boolean = false,
    val replyTo: CommunityComment? = null,
    val error: String? = null
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val communityRepository: CommunityRepository
) : ViewModel() {
    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])
    private val slug: String = checkNotNull(savedStateHandle["slug"])
    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val chapterUrl: String? = savedStateHandle["chapterUrl"]

    private val _tab = MutableStateFlow(if (chapterUrl == null) CommunityTab.REVIEWS else CommunityTab.COMMENTS)
    private val _replyTo = MutableStateFlow<CommunityComment?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val commentsFlow = if (chapterUrl == null) communityRepository.observeMangaComments(mangaId) else communityRepository.observeChapterComments(mangaId, chapterUrl)
    private val reviewsFlow = if (chapterUrl == null) communityRepository.observeReviews(mangaId) else kotlinx.coroutines.flow.flowOf(emptyList())
    private val profileFlow: Flow<CommunityProfile?> = flow { emit(communityRepository.getCurrentProfile()) }

    val state: StateFlow<CommunityUiState> = combine(commentsFlow, reviewsFlow, profileFlow) { comments, reviews, profile ->
        Triple(comments, reviews, profile)
    }.combine(_tab) { triple, tab ->
        Pair(triple, tab)
    }.combine(_replyTo) { pair, replyTo ->
        Triple(pair.first, pair.second, replyTo)
    }.combine(_error) { triple, error ->
        val comments = triple.first.first
        val reviews = triple.first.second
        val profile = triple.first.third
        val tab = triple.second
        val replyTo = triple.third
        CommunityUiState(
            title = if (chapterUrl == null) "مراجعات ومناقشات المانجا" else "نقاش الفصل",
            comments = comments,
            reviews = reviews,
            profile = profile,
            tab = tab,
            chapterMode = chapterUrl != null,
            replyTo = replyTo,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CommunityUiState(chapterMode = chapterUrl != null))

    fun setTab(tab: CommunityTab) { _tab.value = tab }
    fun setReply(comment: CommunityComment?) { _replyTo.value = comment }

    fun postComment(text: String, spoiler: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (chapterUrl == null) {
                    communityRepository.postMangaComment(mangaId, slug, sourceId, text, spoiler, _replyTo.value?.id)
                } else {
                    communityRepository.postChapterComment(mangaId, slug, sourceId, chapterUrl, text, spoiler, _replyTo.value?.id)
                }
            }.onSuccess {
                _replyTo.value = null
                _error.value = null
            }.onFailure { e ->
                _error.value = e.message ?: "فشل إرسال التعليق"
            }
        }
    }

    fun upsertReview(rating: Int, title: String, body: String) {
        viewModelScope.launch {
            runCatching { communityRepository.upsertReview(mangaId, slug, sourceId, rating, title, body) }
                .onFailure { e -> _error.value = e.message ?: "فشل حفظ المراجعة" }
        }
    }
}

@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var commentText by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    var reviewTitle by remember { mutableStateOf("") }
    var reviewBody by remember { mutableStateOf("") }
    var reviewRating by remember { mutableStateOf(5) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text(state.title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(0.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = state.tab == CommunityTab.COMMENTS, onClick = { viewModel.setTab(CommunityTab.COMMENTS) }, label = { Text("التعليقات") })
            if (!state.chapterMode) {
                FilterChip(selected = state.tab == CommunityTab.REVIEWS, onClick = { viewModel.setTab(CommunityTab.REVIEWS) }, label = { Text("المراجعات") })
            }
        }

        if (state.tab == CommunityTab.COMMENTS) {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.comments, key = { it.id }) { comment ->
                    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(comment.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                                    Text(comment.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(comment.replyCount.toString() + " رد", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (comment.spoiler) "[Spoiler] ${comment.text}" else comment.text, color = MangaColors.OnSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.setReply(comment) }) { Text("رد") }
                            }
                        }
                    }
                }
            }
            state.replyTo?.let { reply ->
                Text(
                    text = "الرد على ${reply.authorName}",
                    color = MangaColors.Cyan,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(value = commentText, onValueChange = { commentText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("أضف تعليقاً") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = spoiler, onCheckedChange = { spoiler = it })
                        Text("تعليق يحتوي على حرق", color = MangaColors.OnSurfaceVariant)
                    }
                }
                Button(onClick = {
                    viewModel.postComment(commentText, spoiler)
                    commentText = ""
                    spoiler = false
                }, enabled = commentText.isNotBlank()) {
                    Icon(Icons.Filled.Forum, null)
                    Text("إرسال")
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { reviewDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Star, null)
                    Text("إضافة/تحديث مراجعتك")
                }
                state.reviews.forEach { review ->
                    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(review.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                            Text("${review.rating}/5 • ${review.authorBadge}", color = MangaColors.Cyan)
                            if (review.title.isNotBlank()) Text(review.title, color = MangaColors.OnSurface)
                            if (review.body.isNotBlank()) Text(review.body, color = MangaColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }

    if (reviewDialog) {
        AlertDialog(
            onDismissRequest = { reviewDialog = false },
            title = { Text("مراجعتك") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = reviewTitle, onValueChange = { reviewTitle = it }, label = { Text("العنوان") })
                    OutlinedTextField(value = reviewBody, onValueChange = { reviewBody = it }, label = { Text("التفاصيل") }, minLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            FilterChip(selected = reviewRating == star, onClick = { reviewRating = star }, label = { Text(star.toString()) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.upsertReview(reviewRating, reviewTitle, reviewBody)
                    reviewDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                Button(onClick = { reviewDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
