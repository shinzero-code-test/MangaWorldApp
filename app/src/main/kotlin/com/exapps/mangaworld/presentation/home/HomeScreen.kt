package com.exapps.mangaworld.presentation.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.components.*
import com.exapps.mangaworld.presentation.theme.MangaColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    onSeeAllLatest: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MangaColors.Background)) {
        if (state.isLoading) {
            HomeShimmer()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (state.remoteAlertMessage.isNotBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MangaColors.GlowPurple),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = state.remoteAlertMessage,
                                color = MangaColors.OnSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                // Source selector chips
                item {
                    SourceSelectorRow(
                        sources = state.availableSources,
                        active = state.activeSource,
                        onSelect = viewModel::selectSource
                    )
                }

                // Featured carousel
                if (state.featured.isNotEmpty()) {
                    item {
                        FeaturedCarousel(
                            items = state.featured,
                            onMangaClick = { m -> onMangaClick(m.source.id, m.slug) },
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }

                val layoutVariant = state.homeLayoutVariant.lowercase()
                val trendingFirst = layoutVariant.contains("trending_first")
                val useLatestGrid = layoutVariant.contains("grid")
                if (trendingFirst && state.trending.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "الأكثر قراءة",
                            onSeeAll = { onSeeAllLatest() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        TrendingRow(
                            items = state.trending,
                            onMangaClick = { m -> onMangaClick(m.source.id, m.slug) }
                        )
                    }
                }

                // Latest chapters header
                item {
                    SectionHeader(
                        title = "أحدث الفصول",
                        onSeeAll = onSeeAllLatest,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (useLatestGrid) {
                    if (state.latestChapters.isEmpty()) {
                        item {
                            Text("لا توجد فصول جديدة حالياً", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    } else {
                        item {
                            LatestChapterGrid(
                                items = state.latestChapters.take(10),
                                onMangaClick = { item -> onMangaClick(item.source.id, item.mangaSlug) }
                            )
                        }
                    }
                } else {
                    if (state.latestChapters.isEmpty()) {
                        item {
                            Text("لا توجد فصول جديدة حالياً", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    } else {
                        items(state.latestChapters.take(15), key = { "${it.source.id}_${it.chapterUrl}" }) { item ->
                            LatestChapterRow(
                                item = item,
                                onClick = { onMangaClick(item.source.id, item.mangaSlug) }
                            )
                        }
                    }
                }

                if (state.suggested.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(
                            title = "مقترح لك",
                            onSeeAll = { onSeeAllLatest() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        TrendingRow(
                            items = state.suggested,
                            onMangaClick = { m -> onMangaClick(m.source.id, m.slug) }
                        )
                    }
                }

                // Trending header
                if (!trendingFirst && state.trending.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(
                            title = "الأكثر قراءة",
                            onSeeAll = { onSeeAllLatest() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        TrendingRow(
                            items = state.trending,
                            onMangaClick = { m -> onMangaClick(m.source.id, m.slug) }
                        )
                    }
                }
            }
        }

        // Error snackbar
        state.error?.let { err ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::refresh) {
                        Text("إعادة المحاولة", color = MangaColors.Cyan)
                    }
                }
            ) { Text(err) }
        }
    }
}

// ─── Source Selector ──────────────────────────────────────────────────────────

@Composable
private fun SourceSelectorRow(
    sources: List<MangaSource>,
    active: MangaSource,
    onSelect: (MangaSource) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sources, key = { it.id }) { source ->
            GenreChip(
                label = source.displayName,
                selected = source == active,
                onClick = { onSelect(source) }
            )
        }
    }
}

// ─── Featured Carousel ────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeaturedCarousel(
    items: List<MangaItem>,
    onMangaClick: (MangaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState { items.size }

    // Auto-scroll — pause when user is interacting
    LaunchedEffect(pagerState) {
        while (true) {
            delay(4000)
            if (!pagerState.isScrollInProgress) {
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 8.dp
        ) { page ->
            FeaturedCard(
                manga = items[page],
                onClick = { onMangaClick(items[page]) }
            )
        }
        // Page indicators
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(items.size) { i ->
                val isActive = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (isActive) 20.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) MangaColors.Primary else MangaColors.OutlineVariant)
                )
            }
        }
    }
}

@Composable
private fun FeaturedCard(manga: MangaItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(manga.coverUrl)
                    .crossfade(true)
                    .withFirebaseTrace("featured_cover")
                    .build(),
                imageLoader = ctx.imageLoader,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient overlay
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000)),
                        startY = 60f
                    )
                )
            )
            // Type badge
            if (manga.type != MangaType.UNKNOWN) {
                TypeBadge(manga.type, Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            // Content
            Column(
                Modifier.align(Alignment.BottomStart).padding(12.dp)
            ) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    manga.genres.take(2).forEach { g ->
                        Text(
                            g,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0x80000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── Latest Chapter Row ───────────────────────────────────────────────────────

@Composable
private fun LatestChapterRow(item: LatestChapterItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MangaCover(
            url = item.coverUrl,
            contentDescription = item.mangaTitle,
            modifier = Modifier.size(56.dp, 78.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.mangaTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MangaColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "الفصل ${item.chapterNumber.let { if (it == it.toInt().toFloat()) it.toInt() else it }}",
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.PrimaryLight
            )
            Spacer(Modifier.height(4.dp))
            SourceBadge(item.source)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(item.timeAgo, style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
            if (item.isNew) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "جديد",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(MangaColors.Pink, RoundedCornerShape(100.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
    GradientDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun LatestChapterGrid(
    items: List<LatestChapterItem>,
    onMangaClick: (LatestChapterItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item ->
                    LatestChapterGridCard(
                        item = item,
                        onClick = { onMangaClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LatestChapterGridCard(
    item: LatestChapterItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            MangaCover(
                url = item.coverUrl,
                contentDescription = item.mangaTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    item.mangaTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MangaColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "الفصل ${item.chapterNumber.let { if (it == it.toInt().toFloat()) it.toInt() else it }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.PrimaryLight
                )
                SourceBadge(item.source)
            }
        }
    }
}

// ─── Trending Row ─────────────────────────────────────────────────────────────

@Composable
private fun TrendingRow(items: List<MangaItem>, onMangaClick: (MangaItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        items(items, key = { "trending_${it.id}" }) { manga ->
            MangaCard(
                manga = manga,
                onClick = { onMangaClick(manga) },
                modifier = Modifier.width(120.dp)
            )
        }
    }
}

// ─── Shimmer Loading ──────────────────────────────────────────────────────────

@Composable
private fun HomeShimmer() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            ShimmerBox(Modifier.fillMaxWidth().height(180.dp), RoundedCornerShape(16.dp))
            Spacer(Modifier.height(16.dp))
        }
        items(6) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ShimmerBox(Modifier.size(56.dp, 78.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ShimmerBox(Modifier.fillMaxWidth(0.8f).height(14.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.4f).height(12.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(Modifier.width(70.dp).height(18.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}
