package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource


class LibraryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        val entries = try { entryPoint.widgetDataRepository().getLibraryEntries(limit = 6) } catch (_: Exception) { emptyList() }
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
                LibraryWidgetContent(entries, settings, context)
            }
        }
    }
}

class LibraryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LibraryWidget()
}

@Composable
private fun LibraryWidgetContent(
    entries: List<com.exapps.mangaworld.core.data.LibraryWidgetEntry>,
    settings: WidgetSettingsManager,
    context: Context
) {
    val size = LocalSize.current
    val showCovers = settings.isShowCovers()
    val showTitles = settings.isShowTitles()
    val showBadge = settings.isShowNewBadge()
    val transparentBg = settings.isTransparentBg()
    val visibleCount = settings.getVisibleItemCount(size.height.value.toInt())

    WidgetCard(
        title = stringResource(com.exapps.mangaworld.R.string.widget_library_title),
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (entries.isEmpty()) {
            WidgetEmptyState(
                title = stringResource(com.exapps.mangaworld.R.string.widget_empty_library),
                subtitle = stringResource(com.exapps.mangaworld.R.string.widget_empty_library_hint),
                intent = AppLaunchIntents.home(context),
                actionLabel = stringResource(com.exapps.mangaworld.R.string.widget_browse),
                retryIntent = AppLaunchIntents.home(context)
            )
            return@WidgetCard
        }

        entries.take(visibleCount).forEachIndexed { index, entry ->
            WidgetListItem(
                title = entry.title,
                subtitle = if (showTitles) stringResource(com.exapps.mangaworld.R.string.widget_library_hint) else null,
                trailing = if (showBadge && entry.newChapterCount > 0) "+${entry.newChapterCount}" else null,
                showTitle = showTitles,
                showBadge = showBadge,
                intent = AppLaunchIntents.detail(context, entry.sourceId, entry.slug)
            )
            if (index < visibleCount - 1) {
                Spacer(GlanceModifier.height(8.dp))
            }
        }
    }
}

