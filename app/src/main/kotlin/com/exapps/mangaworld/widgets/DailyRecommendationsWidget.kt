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
import com.exapps.mangaworld.core.data.RemoteWidgetsSnapshot
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

class DailyRecommendationsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetDataRepository()
        val snapshot = repo.getRemoteSnapshot()
        provideContent {
            MangaWidgetTheme(context) {
                DailyRecommendationsContent(snapshot)
            }
        }
    }
}

class DailyRecommendationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyRecommendationsWidget()
}

@Composable
private fun DailyRecommendationsContent(snapshot: RemoteWidgetsSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetCard(title = "اقتراحات اليوم") {
        if (snapshot.recommendation == null && snapshot.trending == null && snapshot.latestUpdates.isEmpty()) {
            WidgetEmptyState(
                title = "لا توجد اقتراحات حالياً",
                subtitle = "سيتم تحديثها تلقائياً",
                intent = AppLaunchIntents.home(context),
                actionLabel = "افتح التطبيق"
            )
            return@WidgetCard
        }

        snapshot.recommendation?.let { recommendation ->
            WidgetListItem(
                title = recommendation.title,
                subtitle = recommendation.subtitle ?: "موصى بها لك",
                trailing = "اقتراح",
                intent = AppLaunchIntents.detail(context, recommendation.sourceId, recommendation.slug)
            )
            Spacer(GlanceModifier.height(8.dp))
        }

        snapshot.trending?.let { trending ->
            WidgetListItem(
                title = trending.title,
                subtitle = trending.subtitle ?: "الأكثر رواجاً",
                trailing = "ترند",
                intent = AppLaunchIntents.detail(context, trending.sourceId, trending.slug)
            )
            Spacer(GlanceModifier.height(8.dp))
        }

        if (size.height >= 200.dp) {
            snapshot.latestUpdates.firstOrNull()?.let { update ->
                WidgetListItem(
                    title = update.mangaTitle,
                    subtitle = update.chapterLabel,
                    trailing = update.timeAgo,
                    intent = AppLaunchIntents.reader(context, update.sourceId, update.mangaId, update.chapterUrl)
                )
            }
        }
    }
}
