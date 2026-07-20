package com.exapps.mangaworld.presentation.stats
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    onBack: () -> Unit,
    viewModel: ReadingStatsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reading_stats), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MangaColors.Cyan)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Overview cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.reading_time),
                        value = "${state.totalReadingHours}h ${state.totalReadingMinutes}m",
                        color = MangaColors.Cyan
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.read_manga),
                        value = "${state.totalMangaRead}",
                        color = MangaColors.GlowPurple
                    )
                }
            }

            // Streak card
            item {
                StreakCard(
                    currentStreak = state.currentStreak,
                    longestStreak = state.longestStreak
                )
            }

            // Today & This Week
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.today),
                        value = stringResource(R.string.fmt_043, state.todayPages),
                        color = MangaColors.PrimaryLight
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.this_week),
                        value = stringResource(R.string.fmt_043, state.thisWeekPages),
                        color = MangaColors.Cyan
                    )
                }
            }

            // Average
            item {
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.avg_pages_day),
                    value = stringResource(R.string.fmt_043, state.averagePagesPerDay),
                    color = MangaColors.Muted
                )
            }

            // Daily chart (simple bar chart)
            if (state.dailyStats.isNotEmpty()) {
                var showReadingTimeChart by remember { mutableStateOf(false) }

                item {
                    Text(
                        stringResource(R.string.daily_details),
                        style = MaterialTheme.typography.titleMedium,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Toggle between pages and reading time
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showReadingTimeChart,
                            onClick = { showReadingTimeChart = false },
                            label = { Text(stringResource(R.string.total_pages), style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(10.dp)
                        )
                        FilterChip(
                            selected = showReadingTimeChart,
                            onClick = { showReadingTimeChart = true },
                            label = { Text(stringResource(R.string.reading_time), style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                item {
                    SimpleBarChart(
                        data = state.dailyStats,
                        useReadingTime = showReadingTimeChart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )
                }
            }

            // Daily breakdown list
            if (state.dailyStats.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.daily_details),
                        style = MaterialTheme.typography.titleMedium,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(state.recentDailyStats) { stat ->
                    DailyStatRow(stat)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.OnSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StreakCard(
    currentStreak: Int,
    longestStreak: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = if (currentStreak > 0) MangaColors.GlowPurple else MangaColors.Muted,
                modifier = Modifier.size(48.dp)
            )
            Column {
                Text(
                    stringResource(R.string.reading_sequence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurfaceVariant
                )
                Text(
                    stringResource(R.string.fmt_020, currentStreak),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (currentStreak > 0) MangaColors.GlowPurple else MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.fmt_045, longestStreak),
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SimpleBarChart(
    data: List<com.exapps.mangaworld.core.data.DailyStat>,
    useReadingTime: Boolean = false,
    modifier: Modifier = Modifier
) {
    val maxValue = if (useReadingTime) {
        data.maxOfOrNull { it.readingTimeMs }?.coerceAtLeast(1) ?: 1
    } else {
        data.maxOfOrNull { it.pagesRead }?.coerceAtLeast(1) ?: 1
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MangaColors.Surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        data.forEach { stat ->
            val value = if (useReadingTime) stat.readingTimeMs else stat.pagesRead.toLong()
            val heightFraction = if (maxValue > 0) value.toFloat() / maxValue else 0f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(heightFraction.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(
                        if (stat.pagesRead > 0) MangaColors.Cyan.copy(alpha = 0.7f)
                        else MangaColors.Muted.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

@Composable
private fun DailyStatRow(stat: com.exapps.mangaworld.core.data.DailyStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stat.displayDate,
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.fmt_043, stat.pagesRead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MangaColors.Cyan
                )
                Text(
                    stringResource(R.string.fmt_039, stat.readingTimeMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MangaColors.OnSurfaceVariant
                )
            }
        }
    }
}
