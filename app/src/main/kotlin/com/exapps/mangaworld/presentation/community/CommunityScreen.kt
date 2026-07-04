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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.presentation.theme.MangaColors

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.Filled.Forum, "المحادثة", tint = MangaColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab selector
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.tab == CommunityTab.COMMENTS,
                    onClick = { viewModel.setTab(CommunityTab.COMMENTS) },
                    label = { Text("التعليقات") },
                    shape = RoundedCornerShape(10.dp)
                )
                // Reviews tab always visible (not just for manga-level)
                FilterChip(
                    selected = state.tab == CommunityTab.REVIEWS,
                    onClick = { viewModel.setTab(CommunityTab.REVIEWS) },
                    label = { Text("المراجعات") },
                    shape = RoundedCornerShape(10.dp)
                )
            }

            when (state.tab) {
                CommunityTab.COMMENTS -> {
                    // Comments list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.comments.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("لا توجد تعليقات بعد", color = MangaColors.Muted)
                                }
                            }
                        }
                        items(state.comments, key = { it.id }) { comment ->
                            CommentCard(
                                comment = comment,
                                isFocused = state.focusCommentId == comment.id,
                                isSpoilerRevealed = comment.id in expandedSpoilers,
                                spoilerDefault = state.appSettings.spoilerCollapseDefault,
                                onRevealSpoiler = { expandedSpoilers.add(comment.id) },
                                onReply = { viewModel.setReply(comment) },
                                onReport = { reportTarget = comment; reportReason = "" },
                                onMute = { viewModel.muteUser(comment.authorUid) },
                                onProfileClick = { onOpenProfile(comment.authorUid) }
                            )
                        }
                    }

                    // Reply indicator
                    state.replyTo?.let { reply ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MangaColors.Cyan.copy(alpha = 0.1f))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Reply, null, tint = MangaColors.Cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("الرد على ${reply.authorName}", color = MangaColors.Cyan, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { viewModel.setReply(null) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = MangaColors.Muted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Comment input
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = commentText,
                                onValueChange = { commentText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("أضف تعليقاً...") },
                                maxLines = 4,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MangaColors.Cyan,
                                    unfocusedBorderColor = MangaColors.Muted.copy(alpha = 0.3f),
                                    cursorColor = MangaColors.Cyan,
                                    focusedTextColor = MangaColors.OnSurface,
                                    unfocusedTextColor = MangaColors.OnSurface,
                                    focusedContainerColor = MangaColors.Surface,
                                    unfocusedContainerColor = MangaColors.Surface
                                )
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = spoiler, onCheckedChange = { spoiler = it })
                                    Text("حرق", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = {
                                        viewModel.postComment(commentText, spoiler)
                                        commentText = ""
                                        spoiler = false
                                    },
                                    enabled = commentText.isNotBlank(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)
                                ) {
                                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("إرسال", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                CommunityTab.REVIEWS -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Add review button
                        item {
                            Button(
                                onClick = { reviewDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)
                            ) {
                                Icon(Icons.Filled.Star, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة/تحديث مراجعتك")
                            }
                        }

                        if (state.reviews.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("لا توجد مراجعات بعد", color = MangaColors.Muted)
                                }
                            }
                        }

                        items(state.reviews, key = { it.id }) { review ->
                            ReviewCard(review = review, onProfileClick = { onOpenProfile(review.authorUid) })
                        }
                    }
                }
            }

            // Error message
            state.error?.let {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Error.copy(alpha = 0.1f))
                ) {
                    Text(it, color = MangaColors.Error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // Review dialog
    if (reviewDialog) {
        AlertDialog(
            onDismissRequest = { reviewDialog = false },
            containerColor = MangaColors.Surface,
            title = { Text("مراجعتك", color = MangaColors.OnSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reviewTitle, onValueChange = { reviewTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("عنوان المراجعة") },
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = reviewBody, onValueChange = { reviewBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("تفاصيل المراجعة") },
                        minLines = 4,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Text("التقييم", color = MangaColors.OnSurface, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            FilterChip(
                                selected = reviewRating == star,
                                onClick = { reviewRating = star },
                                label = { Text("$star") },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.upsertReview(reviewRating, reviewTitle, reviewBody)
                        reviewDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan)
                ) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { reviewDialog = false }) { Text("إلغاء", color = MangaColors.Muted) }
            }
        )
    }

    // Report dialog
    reportTarget?.let { comment ->
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            containerColor = MangaColors.Surface,
            title = { Text("الإبلاغ عن تعليق", color = MangaColors.OnSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(comment.text, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = reportReason, onValueChange = { reportReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("سبب الإبلاغ") },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.reportComment(comment, reportReason); reportTarget = null },
                    enabled = reportReason.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)
                ) { Text("إرسال") }
            },
            dismissButton = {
                TextButton(onClick = { reportTarget = null }) { Text("إلغاء", color = MangaColors.Muted) }
            }
        )
    }
}

@Composable
private fun CommentCard(
    comment: CommunityComment,
    isFocused: Boolean,
    isSpoilerRevealed: Boolean,
    spoilerDefault: Boolean,
    onRevealSpoiler: () -> Unit,
    onReply: () -> Unit,
    onReport: () -> Unit,
    onMute: () -> Unit,
    onProfileClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MangaColors.GlowPurple else MangaColors.SurfaceContainer
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Avatar placeholder
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(MangaColors.Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(comment.authorName.take(1), color = MangaColors.Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                    Column {
                        Text(comment.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onProfileClick))
                        Text(comment.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (comment.replyCount > 0) {
                    Text("${comment.replyCount} رد", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Content
            if (comment.spoiler && spoilerDefault && !isSpoilerRevealed) {
                OutlinedButton(onClick = onRevealSpoiler, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("إظهار السبويْلر")
                }
            } else {
                Text(comment.text, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReply, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Filled.Reply, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("رد", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onReport, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Filled.Flag, null, modifier = Modifier.size(14.dp), tint = MangaColors.Yellow)
                    Spacer(Modifier.width(4.dp))
                    Text("إبلاغ", style = MaterialTheme.typography.labelSmall, color = MangaColors.Yellow)
                }
                TextButton(onClick = onMute, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Filled.VolumeOff, null, modifier = Modifier.size(14.dp), tint = MangaColors.Muted)
                    Spacer(Modifier.width(4.dp))
                    Text("كتم", style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(review: MangaReview, onProfileClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(MangaColors.Yellow.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Star, null, tint = MangaColors.Yellow, modifier = Modifier.size(16.dp))
                }
                Column {
                    Text(review.authorName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onProfileClick))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${review.rating}/5", color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("•", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                        Text(review.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (review.title.isNotBlank()) {
                Text(review.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            if (review.body.isNotBlank()) {
                Text(review.body, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
