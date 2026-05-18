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

class LibraryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 180.dp), DpSize(240.dp, 220.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetDataRepository()
        val entries = repo.getLibraryEntries(limit = 4)
        provideContent {
            MangaWidgetTheme(context) {
                LibraryWidgetContent(entries)
            }
        }
    }
}

class LibraryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LibraryWidget()
}

@Composable
private fun LibraryWidgetContent(entries: List<com.exapps.mangaworld.core.data.LibraryWidgetEntry>) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetCard(title = "مكتبتي") {
        if (entries.isEmpty()) {
            WidgetEmptyState(
                title = "لا توجد عناصر في المكتبة",
                subtitle = "أضف مانجا للمفضلة لتظهر هنا",
                intent = AppLaunchIntents.home(context),
                actionLabel = "تصفح"
            )
            return@WidgetCard
        }

        val visibleCount = if (size.height < 210.dp) 2 else 3
        entries.take(visibleCount).forEachIndexed { index, entry ->
            WidgetListItem(
                title = entry.title,
                subtitle = "آخر فتح من المكتبة",
                trailing = if (entry.newChapterCount > 0) "+${entry.newChapterCount}" else "0",
                intent = AppLaunchIntents.detail(context, entry.sourceId, entry.slug)
            )
            if (index < visibleCount - 1) {
                Spacer(GlanceModifier.height(8.dp))
            }
        }
    }
}
