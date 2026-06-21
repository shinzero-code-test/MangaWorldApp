package com.exapps.mangaworld.presentation.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors

/**
 * Redesigned sources screen — displays all 19 sources in a grid view
 * with site title and logo icon. New v4.0.0 sources are prominently shown.
 * Tapping a source links to its search/browse functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onSourceSearch: (sourceId: String) -> Unit = {},
    onSourceBrowse: (sourceId: String) -> Unit = {},
    enabledSources: Set<String> = MangaSource.entries.map { it.id }.toSet(),
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> }
) {
    var showToggleMode by remember { mutableStateOf(false) }

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
                actions = {
                    IconButton(onClick = { showToggleMode = !showToggleMode }) {
                        Icon(
                            if (showToggleMode) Icons.Filled.Explore else Icons.Filled.OpenInNew,
                            contentDescription = if (showToggleMode) "تصفح" else "إخفاء المصادر",
                            tint = MangaColors.Cyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Section header for original sources
            item(span = { GridItemSpan(3) }) {
                Text(
                    "المصادر الأصلية",
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Original 5 sources
            val originalSources = listOf(
                MangaSource.OLYMPUS, MangaSource.AZORA, MangaSource.STARZ,
                MangaSource.MANGASID, MangaSource.MESHMANGA
            )
            items(originalSources) { source ->
                SourceGridCard(
                    source = source,
                    isEnabled = source.id in enabledSources,
                    isOriginal = true,
                    onClick = {
                        if (showToggleMode) {
                            onToggleSource(source.id, source.id !in enabledSources)
                        } else {
                            onSourceSearch(source.id)
                        }
                    },
                    onToggle = { enabled -> onToggleSource(source.id, enabled) },
                    showToggleMode = showToggleMode
                )
            }

            // Section header for new Arabic sources
            item(span = { GridItemSpan(3) }) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "مصادر عربية جديدة — v4.0.0",
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.Cyan,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // New Madara sources
            val madaraSources = listOf(
                MangaSource.ASQ3, MangaSource.LEKMANGA, MangaSource.LEKMANGAONLINE,
                MangaSource.LIKEMANGA, MangaSource.LINKMANGA,
                MangaSource.MANGALEKO, MangaSource.MANGALIONZ
            )
            items(madaraSources) { source ->
                SourceGridCard(
                    source = source,
                    isEnabled = source.id in enabledSources,
                    isOriginal = false,
                    onClick = {
                        if (showToggleMode) {
                            onToggleSource(source.id, source.id !in enabledSources)
                        } else {
                            onSourceSearch(source.id)
                        }
                    },
                    onToggle = { enabled -> onToggleSource(source.id, enabled) },
                    showToggleMode = showToggleMode
                )
            }

            // Section header for MangaReader sources
            item(span = { GridItemSpan(3) }) {
                Text(
                    "مصادر MangaReader",
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            // MangaReader sources
            val mangaReaderSources = listOf(
                MangaSource.AREASCANS, MangaSource.HIJALA, MangaSource.LAVASCANS,
                MangaSource.STELLARSABER, MangaSource.UMIMANGA
            )
            items(mangaReaderSources) { source ->
                SourceGridCard(
                    source = source,
                    isEnabled = source.id in enabledSources,
                    isOriginal = false,
                    onClick = {
                        if (showToggleMode) {
                            onToggleSource(source.id, source.id !in enabledSources)
                        } else {
                            onSourceSearch(source.id)
                        }
                    },
                    onToggle = { enabled -> onToggleSource(source.id, enabled) },
                    showToggleMode = showToggleMode
                )
            }

            // Section header for custom sources
            item(span = { GridItemSpan(3) }) {
                Text(
                    "مصادر مخصصة",
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            // Custom sources
            val customSources = listOf(MangaSource.PROCOMIC, MangaSource.ROCKMANGA)
            items(customSources) { source ->
                SourceGridCard(
                    source = source,
                    isEnabled = source.id in enabledSources,
                    isOriginal = false,
                    onClick = {
                        if (showToggleMode) {
                            onToggleSource(source.id, source.id !in enabledSources)
                        } else {
                            onSourceSearch(source.id)
                        }
                    },
                    onToggle = { enabled -> onToggleSource(source.id, enabled) },
                    showToggleMode = showToggleMode
                )
            }
        }
    }
}

@Composable
private fun SourceGridCard(
    source: MangaSource,
    isEnabled: Boolean,
    isOriginal: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    showToggleMode: Boolean
) {
    val sourceColor = getSourceColor(source)
    val icon = getSourceIcon(source)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MangaColors.Surface else MangaColors.SurfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Source icon/logo circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                sourceColor.copy(alpha = 0.3f),
                                sourceColor.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = source.displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sourceColor
                )
            }

            // Source name
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled) MangaColors.OnSurface else MangaColors.Muted,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )

            // Theme badge
            Text(
                text = getThemeLabel(source),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MangaColors.Muted,
                textAlign = TextAlign.Center
            )

            // Cloudflare badge or toggle
            if (showToggleMode) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(0.7f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MangaColors.Cyan,
                        checkedTrackColor = MangaColors.Cyan.copy(alpha = 0.3f)
                    )
                )
            } else if (source.requiresVerification) {
                Text(
                    "CF",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MangaColors.Yellow,
                    fontWeight = FontWeight.Bold
                )
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

private fun getSourceIcon(source: MangaSource): ImageVector = when (source.themeType) {
    MangaSource.ThemeType.MADARA -> Icons.Filled.OpenInNew
    MangaSource.ThemeType.MANGAREADER -> Icons.Filled.Search
    MangaSource.ThemeType.ASTRO -> Icons.Filled.Explore
    MangaSource.ThemeType.API -> Icons.Filled.OpenInNew
    MangaSource.ThemeType.CUSTOM -> Icons.Filled.OpenInNew
    MangaSource.ThemeType.MADARA_CUSTOM -> Icons.Filled.OpenInNew
    MangaSource.ThemeType.OTHER -> Icons.Filled.OpenInNew
}

private fun getThemeLabel(source: MangaSource): String = when (source.themeType) {
    MangaSource.ThemeType.MADARA -> "Madara"
    MangaSource.ThemeType.MANGAREADER -> "MangaReader"
    MangaSource.ThemeType.ASTRO -> "Astro"
    MangaSource.ThemeType.API -> "API"
    MangaSource.ThemeType.CUSTOM -> "Next.js"
    MangaSource.ThemeType.MADARA_CUSTOM -> "Madara+"
    MangaSource.ThemeType.OTHER -> "WordPress"
}
