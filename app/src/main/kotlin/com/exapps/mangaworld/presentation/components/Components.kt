package com.exapps.mangaworld.presentation.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.theme.MangaColors

// ─── Manga Cover Card ─────────────────────────────────────────────────────────

@Composable
fun MangaCard(
    manga: MangaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRating: Boolean = true
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            // Cover image
            MangaCover(
                url = manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.67f)
            )
            // Type badge
            if (manga.type != MangaType.UNKNOWN) {
                TypeBadge(
                    type = manga.type,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
            // Rating
            if (showRating && manga.rating != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(Icons.Filled.Star, null, tint = MangaColors.Yellow, modifier = Modifier.size(10.dp))
                    Text("%.1f".format(manga.rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            // New badge
            if (manga.isNew) {
                Text(
                    "جديد",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            Brush.horizontalGradient(MangaColors.GradientPurpleCyan),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // Bottom gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))
                    )
            )
        }
        // Title & info
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                manga.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MangaColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (manga.latestChapter != null) {
                Text(
                    "الفصل ${manga.latestChapter}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.PrimaryLight,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ─── Manga Cover Image ────────────────────────────────────────────────────────

@Composable
fun MangaCover(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap()
) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(url)
            .crossfade(300)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .precision(Precision.INEXACT)
            .withFirebaseTrace("manga_cover")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build(),
        imageLoader = ctx.imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MangaColors.SurfaceContainer)
    )
}

// ─── Type Badge ───────────────────────────────────────────────────────────────

@Composable
fun TypeBadge(type: MangaType, modifier: Modifier = Modifier) {
    val (bg, text) = when (type) {
        MangaType.MANGA  -> Color(0x99000033) to "مانجا"
        MangaType.MANHWA -> Color(0x99001133) to "مانهوا"
        MangaType.MANHUA -> Color(0x99330011) to "مانهوا"
        else             -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// ─── Status Badge ─────────────────────────────────────────────────────────────

@Composable
fun StatusBadge(status: MangaStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        MangaStatus.ONGOING    -> Color(0x2266BB44) to MangaColors.OngoingColor
        MangaStatus.COMPLETED  -> Color(0x22888888) to MangaColors.CompletedColor
        MangaStatus.CANCELLED  -> Color(0x22BB2222) to MaterialTheme.colorScheme.error
        MangaStatus.HIATUS     -> Color(0x22FF9800) to MangaColors.HiatusColor
        MangaStatus.UNKNOWN    -> Color(0x22888888) to MangaColors.Muted
    }
    Text(
        status.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// ─── Source Badge ─────────────────────────────────────────────────────────────

@Composable
fun SourceBadge(source: MangaSource, modifier: Modifier = Modifier) {
    Text(
        source.displayName,
        style = MaterialTheme.typography.labelSmall,
        color = MangaColors.MutedLight,
        modifier = modifier
            .background(MangaColors.SurfaceHighest, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ─── Section Header ───────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MangaColors.OnSurface,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(
                    "عرض الكل",
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.Cyan
                )
            }
        }
    }
}

// ─── Shimmer Effect ───────────────────────────────────────────────────────────

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MangaColors.SurfaceContainer,
                        MangaColors.SurfaceHigh,
                        MangaColors.SurfaceContainer
                    ),
                    start = Offset(translateAnim - 500f, 0f),
                    end = Offset(translateAnim, 0f)
                )
            )
    )
}

// ─── Gradient Divider ─────────────────────────────────────────────────────────

@Composable
fun GradientDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, MangaColors.OutlineVariant, Color.Transparent)
                )
            )
    )
}

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MangaColors.OutlineVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MangaColors.MutedLight,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

// ─── Gradient Button ──────────────────────────────────────────────────────────

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) Brush.horizontalGradient(MangaColors.GradientPurpleCyan)
                else Brush.horizontalGradient(listOf(MangaColors.Muted, MangaColors.Muted))
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ─── Genre Chip ───────────────────────────────────────────────────────────────

@Composable
fun GenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected)
        Brush.horizontalGradient(MangaColors.GradientPurpleCyan)
    else
        Brush.linearGradient(listOf(MangaColors.SurfaceContainer, MangaColors.SurfaceContainer))
    val textColor = if (selected) Color.White else MangaColors.MutedLight
    val borderColor = if (selected) Color.Transparent else MangaColors.OutlineVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .border(if (selected) 0.dp else 1.dp, borderColor, RoundedCornerShape(100.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

// ─── Loading Indicator ────────────────────────────────────────────────────────

@Composable
fun MangaLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = MangaColors.Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(32.dp)
        )
    }
}
