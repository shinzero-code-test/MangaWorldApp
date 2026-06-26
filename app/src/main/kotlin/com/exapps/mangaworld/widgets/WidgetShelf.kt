package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as GlanceColorProvider
import dagger.hilt.android.EntryPointAccessors
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint

class WidgetShelf : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        provideContent {
            MangaWidgetTheme(context) {
                WidgetShelfContent(settings, context)
            }
        }
    }
}

class WidgetShelfReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WidgetShelf()
}

@Composable
private fun WidgetShelfContent(
    settings: WidgetSettingsManager,
    context: Context
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "الوصول السريع",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
        Spacer(GlanceModifier.height(8.dp))

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShelfItem(
                label = "🏠 الرئيسية",
                onClick = actionStartActivity(AppLaunchIntents.home(context))
            )
            Spacer(GlanceModifier.width(6.dp))
            ShelfItem(
                label = "🔍 البحث",
                onClick = actionStartActivity(AppLaunchIntents.search(context))
            )
            Spacer(GlanceModifier.width(6.dp))
            ShelfItem(
                label = "📚 المكتبة",
                onClick = actionStartActivity(AppLaunchIntents.library(context))
            )
            Spacer(GlanceModifier.width(6.dp))
            ShelfItem(
                label = "⬇️ التنزيلات",
                onClick = actionStartActivity(AppLaunchIntents.downloads(context))
            )
        }
    }
}

@Composable
private fun ShelfItem(
    label: String,
    onClick: androidx.glance.appwidget.action.Action
) {
    Box(
        modifier = GlanceModifier
            .padding(4.dp)
            .background(GlanceColorProvider(
                day = android.graphics.Color.parseColor("#1AFFFFFF"),
                night = android.graphics.Color.parseColor("#33FFFFFF")
            ))
            .clickable(onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1
        )
    }
}
