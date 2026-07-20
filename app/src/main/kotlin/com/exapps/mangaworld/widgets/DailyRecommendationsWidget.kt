package com.exapps.mangaworld.widgets

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
import com.exapps.mangaworld.core.data.RemoteWidgetsSnapshot
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

class DailyRecommendationsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        val snapshot = try { entryPoint.widgetDataRepository().getRemoteSnapshot() } catch (_: Exception) { null }
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
                DailyRecommendationsContent(snapshot, settings, context)
            }
        }
    }
}

class DailyRecommendationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyRecommendationsWidget()
}

@Composable
private fun DailyRecommendationsContent(
    snapshot: RemoteWidgetsSnapshot,
    settings: WidgetSettingsManager,
    context: Context
) {
    val size = LocalSize.current
    val showTitles = settings.isShowTitles()
    val showBadge = settings.isShowNewBadge()
    val transparentBg = settings.isTransparentBg()
    val visibleCount = settings.getVisibleItemCount(size.height.value.toInt())

    WidgetCard(
        title = "اقتراحات اليوم",
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (snapshot.recommendation == null && snapshot.trending == null && snapshot.latestUpdates.isEmpty()) {
            WidgetEmptyState(
                title = "لا توجد اقتراحات حالياً",
                subtitle = "سيتم تحديثها تلقائياً",
                intent = AppLaunchIntents.home(context),
                actionLabel = "افتح التطبيق",
                retryIntent = AppLaunchIntents.home(context)
            )
            return@WidgetCard
        }

        var itemCount = 0

        snapshot.recommendation?.let { recommendation ->
            if (itemCount < visibleCount) {
                WidgetListItem(
                    title = recommendation.title,
                    subtitle = if (showTitles) recommendation.subtitle ?: "موصى بها لك" else null,
                    trailing = if (showBadge) "اقتراح" else null,
                    showTitle = showTitles,
                    showBadge = showBadge,
                    intent = AppLaunchIntents.detail(context, recommendation.sourceId, recommendation.slug)
                )
                Spacer(GlanceModifier.height(8.dp))
                itemCount++
            }
        }

        snapshot.trending?.let { trending ->
            if (itemCount < visibleCount) {
                WidgetListItem(
                    title = trending.title,
                    subtitle = if (showTitles) trending.subtitle ?: "الأكثر رواجاً" else null,
                    trailing = if (showBadge) "ترند" else null,
                    showTitle = showTitles,
                    showBadge = showBadge,
                    intent = AppLaunchIntents.detail(context, trending.sourceId, trending.slug)
                )
                Spacer(GlanceModifier.height(8.dp))
                itemCount++
            }
        }

        if (itemCount < visibleCount) {
            snapshot.latestUpdates.firstOrNull()?.let { update ->
                WidgetListItem(
                    title = update.mangaTitle,
                    subtitle = if (showTitles) update.chapterLabel else null,
                    trailing = if (showBadge) update.timeAgo else null,
                    showTitle = showTitles,
                    showBadge = showBadge,
                    intent = AppLaunchIntents.reader(context, update.sourceId, update.mangaId, update.chapterUrl)
                )
            }
        }
    }
}
