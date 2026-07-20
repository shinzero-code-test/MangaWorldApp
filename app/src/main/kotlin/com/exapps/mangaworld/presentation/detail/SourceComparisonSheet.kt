
package com.exapps.mangaworld.presentation.detail
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



data class SourceComparison(
    val source: MangaSource,
    val match: MangaItem?,
    val chapterCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)

@Composable
fun SourceComparisonSheet(
    currentSource: MangaSource,
    otherSources: List<SourceComparison>,
    onSourceSelected: (MangaSource, String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.compare_sources),
                    style = MaterialTheme.typography.titleLarge,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Filled.CompareArrows,
                    contentDescription = null,
                    tint = MangaColors.Cyan
                )
            }

            Text(
                stringResource(R.string.choose_source_to_read),
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurfaceVariant
            )

            // Current source
            SourceCard(
                source = currentSource,
                isCurrentSource = true,
                chapterCount = null,
                onClick = null
            )

            // Other sources
            otherSources.forEach { comparison ->
                SourceCard(
                    source = comparison.source,
                    isCurrentSource = false,
                    chapterCount = comparison.chapterCount,
                    isLoading = comparison.isLoading,
                    error = comparison.error,
                    onClick = comparison.match?.let { match ->
                        { onSourceSelected(comparison.source, match.slug) }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SourceCard(
    source: MangaSource,
    isCurrentSource: Boolean,
    chapterCount: Int?,
    isLoading: Boolean = false,
    error: String? = null,
    onClick: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentSource) MangaColors.Cyan.copy(alpha = 0.1f)
            else MangaColors.SurfaceContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        source.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCurrentSource) {
                        Text(
                            stringResource(R.string.str_005),
                            style = MaterialTheme.typography.labelSmall,
                            color = MangaColors.Cyan
                        )
                    }
                }
                Text(
                    source.baseUrl.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurfaceVariant
                )
            }

            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MangaColors.Cyan,
                        strokeWidth = 2.dp
                    )
                }
                error != null -> {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MangaColors.Error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                chapterCount != null -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.fmt_017, chapterCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MangaColors.Cyan,
                            fontWeight = FontWeight.Bold
                        )
                        if (onClick != null) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.select),
                                tint = MangaColors.Cyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        stringResource(R.string.unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaColors.Muted
                    )
                }
            }
