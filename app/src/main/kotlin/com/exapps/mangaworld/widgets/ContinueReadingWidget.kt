package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
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
        val settings = entryPoint.widgetSettingsManager()
        val data = try { entryPoint.widgetDataRepository().getContinueReading() } catch (_: Exception) { null }
        val cover = try { entryPoint.widgetDataRepository().loadCoverBitmap(data?.coverUrl, width = 320, height = 440) } catch (_: Exception) { null }
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
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
                actionLabel = "افتح التطبيق",
                retryIntent = AppLaunchIntents.home(context)
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
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            text = data.chapterLabel,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp
            )
        )
        Spacer(GlanceModifier.height(8.dp))

        // Progress bar
        if (data.progressPercent > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(GlanceTheme.colors.surfaceVariant)
                        .cornerRadius(3.dp)
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(3.dp)
                            .background(GlanceTheme.colors.primary)
                    ) {}
                }
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "${data.progressPercent}%",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(GlanceModifier.height(10.dp))
        } else {
            Spacer(GlanceModifier.height(12.dp))
        }

        WidgetPrimaryButton(
            label = "متابعة القراءة",
            intent = AppLaunchIntents.reader(context, data.sourceId, data.mangaId, data.chapterUrl)
        )
    }
}
