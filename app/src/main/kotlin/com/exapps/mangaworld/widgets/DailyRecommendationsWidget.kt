package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource


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
        title = stringResource(com.exapps.mangaworld.R.string.widget_today_recommendations),
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (snapshot.recommendation == null && snapshot.trending == null && snapshot.latestUpdates.isEmpty()) {
            WidgetEmptyState(
                title = stringResource(com.exapps.mangaworld.R.string.widget_empty_recommendations),
                subtitle = stringResource(com.exapps.mangaworld.R.string.widget_empty_recommendations_hint),
                intent = AppLaunchIntents.home(context),
                actionLabel = stringResource(com.exapps.mangaworld.R.string.widget_open_app),
                retryIntent = AppLaunchIntents.home(context)
            )
            return@WidgetCard
        }

        var itemCount = 0

        snapshot.recommendation?.let { recommendation ->
            if (itemCount < visibleCount) {
                WidgetListItem(
                    title = recommendation.title,
                    subtitle = if (showTitles) recommendation.subtitle ?: stringResource(com.exapps.mangaworld.R.string.widget_recommended_for_you) else null,
                    trailing = if (showBadge) stringResource(com.exapps.mangaworld.R.string.widget_recommendation_badge) else null,
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
                    subtitle = if (showTitles) trending.subtitle ?: stringResource(com.exapps.mangaworld.R.string.widget_trending) else null,
                    trailing = if (showBadge) stringResource(com.exapps.mangaworld.R.string.widget_trending_badge) else null,
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
