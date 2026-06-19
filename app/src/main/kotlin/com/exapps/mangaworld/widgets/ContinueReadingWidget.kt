package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.exapps.mangaworld.core.data.ContinueReadingWidgetData
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

class ContinueReadingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(140.dp, 140.dp), DpSize(180.dp, 220.dp), DpSize(240.dp, 240.dp))
    )

    override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repo = entryPoint.widgetDataRepository()
        val settings = entryPoint.widgetSettingsManager()
        val data = repo.getContinueReading()
        val cover = repo.loadCoverBitmap(data?.coverUrl, width = 320, height = 440)
        provideContent {
            MangaWidgetTheme(context) {
                ContinueReadingContent(data = data, cover = cover, settings = settings, context = context)
            }
        }
    }
}

class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}

@Composable
private fun ContinueReadingContent(
    data: ContinueReadingWidgetData?,
    cover: android.graphics.Bitmap?,
    settings: WidgetSettingsManager,
    context: Context
) {
    val size = LocalSize.current
    val showCover = settings.isShowCovers()
    val showTitles = settings.isShowTitles()
    val transparentBg = settings.isTransparentBg()

    WidgetCard(
        title = "تابع القراءة",
        showTitle = showTitles,
        transparentBg = transparentBg
    ) {
        if (data == null) {
            WidgetEmptyState(
                title = "لا توجد قراءة حالية",
                subtitle = "ابدأ قراءة مانجا لتظهر هنا",
                intent = AppLaunchIntents.home(context),
                actionLabel = "افتح التطبيق"
            )
            return@WidgetCard
        }

        val imageProvider = cover?.let { ImageProvider(it) }
        if (imageProvider != null) {
            WidgetCover(provider = imageProvider, description = data.title, showCover = showCover)
            Spacer(GlanceModifier.height(10.dp))
        }
        if (showTitles) {
            Text(
                text = data.title,
                maxLines = if (size.width < 170.dp) 1 else 2,
                style = TextStyle(
                    color = androidx.glance.GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            text = data.chapterLabel,
            style = TextStyle(
                color = androidx.glance.GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp
            )
        )
        Spacer(GlanceModifier.height(12.dp))
        WidgetPrimaryButton(
            label = "متابعة القراءة",
            intent = AppLaunchIntents.reader(context, data.sourceId, data.mangaId, data.chapterUrl)
        )
    }
}
