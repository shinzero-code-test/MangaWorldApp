package com.exapps.mangaworld.presentation.sources

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors

/**
 * Redesigned sources screen — displays ALL sources in a unified grid view
 * with real site logos under the heading "المصادر العربية".
 * Tapping a source navigates to its dedicated SourceBrowseScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onSourceClick: (sourceId: String) -> Unit = {}
) {
    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("المصادر", color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
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
                .padding(horizontal = 12.dp)
        ) {
            // Section header
            Text(
                "المصادر العربية",
                style = MaterialTheme.typography.titleMedium,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Unified grid for ALL sources
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(MangaSource.entries) { source ->
                    SourceGridCard(
                        source = source,
                        onClick = { onSourceClick(source.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceGridCard(
    source: MangaSource,
    onClick: () -> Unit
) {
    val sourceColor = getSourceColor(source)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Real site logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                sourceColor.copy(alpha = 0.15f),
                                MangaColors.SurfaceContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (source.logoDrawableRes != 0) {
                    Image(
                        painter = painterResource(id = source.logoDrawableRes),
                        contentDescription = source.displayName,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = source.displayName.take(2),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = sourceColor
                    )
                }
            }

            // Source name
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )

            // Domain hint
            Text(
                text = source.baseUrl.removePrefix("https://").removePrefix("http://").take(18),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MangaColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // CF badge if protected
            if (source.requiresVerification) {
                Text(
                    "CF",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Yellow,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun getSourceColor(source: MangaSource): Color = when (source.themeType) {
    MangaSource.ThemeType.MADARA -> MangaColors.Pink
    MangaSource.ThemeType.MANGAREADER -> MangaColors.Cyan
    MangaSource.ThemeType.ASTRO -> MangaColors.Primary
    MangaSource.ThemeType.API -> MangaColors.Green
    MangaSource.ThemeType.CUSTOM -> MangaColors.Yellow
    MangaSource.ThemeType.MADARA_CUSTOM -> MangaColors.Orange
    MangaSource.ThemeType.OTHER -> MangaColors.Muted
}
