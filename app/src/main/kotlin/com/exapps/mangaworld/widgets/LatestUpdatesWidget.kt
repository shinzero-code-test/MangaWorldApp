package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        val snapshot = try { entryPoint.widgetDataRepository().getRemoteSnapshot() } catch (_: Exception) { null }
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
                LatestUpdatesContent(snapshot.latestUpdates, settings, context)
            }
        }
    }
}

class LatestUpdatesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LatestUpdatesWidget()
}

@Composable
private fun LatestUpdatesContent(
    updates: List<com.exapps.mangaworld.core.data.WidgetLatestUpdateEntry>,
    settings: WidgetSettingsManager,
    context: Context
) {
    val size = LocalSize.current
    val showTitles = settings.isShowTitles()
    val showBadge = settings.isShowNewBadge()
    val transparentBg = settings.isTransparentBg()
    val visibleCount = settings.getVisibleItemCount(size.height.value.toInt())

    WidgetCard(
        title = stringResource(com.exapps.mangaworld.R.string.widget_latest_title),
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (updates.isEmpty()) {
            WidgetEmptyState(
                title = stringResource(com.exapps.mangaworld.R.string.widget_empty_latest),
                subtitle = stringResource(com.exapps.mangaworld.R.string.widget_empty_latest_hint),
                intent = AppLaunchIntents.latestUpdates(context),
                actionLabel = stringResource(com.exapps.mangaworld.R.string.widget_view_updates),
                retryIntent = AppLaunchIntents.latestUpdates(context)
            )
            return@WidgetCard
        }

        updates.take(visibleCount).forEachIndexed { index, update ->
            WidgetListItem(
                title = update.mangaTitle,
                subtitle = if (showTitles) update.chapterLabel else null,
                trailing = if (showBadge) update.timeAgo else null,
                showTitle = showTitles,
                showBadge = showBadge,
                // Only the newest entry (index 0) shows an active red status dot,
                // matching the single lit dot next to the top row in the mock.
                leadingDotActive = index == 0,
                intent = AppLaunchIntents.reader(context, update.sourceId, update.mangaId, update.chapterUrl)
            )
            if (index < visibleCount - 1) {
                Spacer(GlanceModifier.height(8.dp))
            }
        }
    }
}
