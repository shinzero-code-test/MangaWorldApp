package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

class LatestUpdatesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetDataRepository()
        val snapshot = repo.getRemoteSnapshot()
        provideContent {
            MangaWidgetTheme(context) {
                LatestUpdatesContent(snapshot.latestUpdates)
            }
        }
    }
}

class LatestUpdatesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LatestUpdatesWidget()
}

@Composable
private fun LatestUpdatesContent(updates: List<com.exapps.mangaworld.core.data.WidgetLatestUpdateEntry>) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetCard(title = "أحدث التحديثات") {
        if (updates.isEmpty()) {
            WidgetEmptyState(
                title = "لا توجد تحديثات حالياً",
                subtitle = "سيتم جلبها تلقائياً",
                intent = AppLaunchIntents.latestUpdates(context),
                actionLabel = "عرض التحديثات"
            )
            return@WidgetCard
        }

        val visibleCount = if (size.height < 210.dp) 2 else 3
        updates.take(visibleCount).forEachIndexed { index, update ->
            WidgetListItem(
                title = update.mangaTitle,
                subtitle = update.chapterLabel,
                trailing = update.timeAgo,
                intent = AppLaunchIntents.reader(context, update.sourceId, update.mangaId, update.chapterUrl)
            )
            if (index < visibleCount - 1) {
                Spacer(GlanceModifier.height(8.dp))
            }
        }
    }
}
