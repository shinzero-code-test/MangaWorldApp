package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource


class ReadingStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 260.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        val stats = try { entryPoint.widgetDataRepository().getReadingStats() } catch (_: Exception) { ReadingStatsWidgetData(0, 0, 0) }
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
                ReadingStatsContent(stats, settings, context)
            }
        }
    }
}

class ReadingStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingStatsWidget()
}

@Composable
private fun ReadingStatsContent(
    stats: ReadingStatsWidgetData,
    settings: WidgetSettingsManager,
    context: Context
) {
    val showTitles = settings.isShowTitles()
    val transparentBg = settings.isTransparentBg()

    WidgetCard(
        title = stringResource(com.exapps.mangaworld.R.string.widget_stats_section),
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (stats.totalChaptersRead == 0 && stats.totalReadingMinutes == 0L) {
            WidgetEmptyState(
                title = stringResource(com.exapps.mangaworld.R.string.widget_empty_stats),
                subtitle = stringResource(com.exapps.mangaworld.R.string.widget_empty_stats_hint),
                intent = AppLaunchIntents.home(context),
                actionLabel = stringResource(com.exapps.mangaworld.R.string.widget_start_reading),
                retryIntent = AppLaunchIntents.home(context)
            )
            return@WidgetCard
        }

        StatsRow(icon = "🔥", label = stringResource(com.exapps.mangaworld.R.string.widget_reading_streak), value = "${stats.readingStreakDays} يوم")
        Spacer(GlanceModifier.height(8.dp))
        StatsRow(icon = "📖", label = stringResource(com.exapps.mangaworld.R.string.widget_chapters_read), value = stats.totalChaptersRead.toString())
        Spacer(GlanceModifier.height(8.dp))
        StatsRow(icon = "⏱️", label = stringResource(com.exapps.mangaworld.R.string.widget_reading_time), value = formatMinutes(stats.totalReadingMinutes))
    }
}

@Composable
private fun StatsRow(icon: String, label: String, value: String) {
    Row(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Small red icon chip, echoing the circular flame/book/clock badges
        // shown next to each stat row in the preview mock.
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(8.dp)
                .padding(4.dp)
        ) {
            Text(text = icon, style = TextStyle(fontSize = 12.sp))
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp
            ),
            modifier = GlanceModifier.fillMaxWidth()
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

private fun formatMinutes(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}س ${minutes}د" else "${minutes} دقيقة"
}

