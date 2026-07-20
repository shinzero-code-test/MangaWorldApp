package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.exapps.mangaworld.core.data.ReadingStatsWidgetData
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

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
        title = "إحصائيات القراءة",
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (stats.totalChaptersRead == 0 && stats.totalReadingMinutes == 0L) {
            WidgetEmptyState(
                title = "لا توجد إحصائيات بعد",
                subtitle = "ابدأ القراءة لتظهر إنجازاتك",
                intent = AppLaunchIntents.home(context),
                actionLabel = "ابدأ الآن"
            )
            return@WidgetCard
        }

        StatsRow(icon = "🔥", label = "سلسلة القراءة", value = "${stats.readingStreakDays} يوم")
        Spacer(GlanceModifier.height(8.dp))
        StatsRow(icon = "📖", label = "الفصول المقروءة", value = stats.totalChaptersRead.toString())
        Spacer(GlanceModifier.height(8.dp))
        StatsRow(icon = "⏱️", label = "وقت القراءة", value = formatMinutes(stats.totalReadingMinutes))
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
