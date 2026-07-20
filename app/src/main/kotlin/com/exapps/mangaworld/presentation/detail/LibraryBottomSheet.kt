package com.exapps.mangaworld.presentation.detail

import android.content.Context
import com.exapps.mangaworld.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exapps.mangaworld.domain.model.ReadingListStatus
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun LibraryBottomSheet(
    isFavourite: Boolean,
    currentStatus: String?,
    onToggleFavourite: () -> Unit,
    onSetStatus: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    LocalContext.current.getString(R.string.library_section_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, LocalContext.current.getString(R.string.close), tint = MangaColors.Muted)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Favourite toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MangaColors.SurfaceContainer)
                    .clickable {
                        if (isFavourite) {
                            onSetStatus(null)
                            onToggleFavourite()
                        } else {
                            onToggleFavourite()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavourite) MangaColors.Pink else MangaColors.Muted,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isFavourite) LocalContext.current.getString(R.string.in_favorites) else LocalContext.current.getString(R.string.add_to_favorites),
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (isFavourite) LocalContext.current.getString(R.string.tap_remove_favorite) else LocalContext.current.getString(R.string.tap_to_add),
                        color = MangaColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Divider
            HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.2f))

            Spacer(Modifier.height(16.dp))

            // Status label
            Text(
                LocalContext.current.getString(R.string.reading_list),
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(12.dp))

            // Status buttons in 2-column grid
            val statuses = listOf(
                ReadingListStatus.READING to Pair(Icons.Filled.AutoStories, LocalContext.current.getString(R.string.library_reading)),
                ReadingListStatus.COMPLETED to Pair(Icons.Filled.CheckCircle, LocalContext.current.getString(R.string.library_read)),
                ReadingListStatus.PLAN_TO_READ to Pair(Icons.Filled.Schedule, LocalContext.current.getString(R.string.library_plan_to_read)),
                ReadingListStatus.ON_HOLD to Pair(Icons.Filled.PauseCircle, LocalContext.current.getString(R.string.library_on_hold)),
                ReadingListStatus.DROPPED to Pair(Icons.Filled.Cancel, LocalContext.current.getString(R.string.library_dropped))
            )

            val rows = statuses.chunked(2)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { (status, pair) ->
                        val (icon, label) = pair
                        val isSelected = currentStatus == status.label
                        StatusButton(
                            icon = icon,
                            label = label,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (isSelected) {
                                    onSetStatus(null)
                                } else {
                                    // Ensure manga is in favourites first
                                    if (!isFavourite) {
                                        onToggleFavourite()
                                    }
                                    onSetStatus(status.label)
                                }
                            }
                        )
                    }
                    // Fill remaining space if odd number
                    if (row.size < 2) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MangaColors.Cyan.copy(alpha = 0.15f) else MangaColors.SurfaceContainer
    val iconTint = if (isSelected) MangaColors.Cyan else MangaColors.Muted
    val textColor = if (isSelected) MangaColors.Cyan else MangaColors.OnSurfaceVariant

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
