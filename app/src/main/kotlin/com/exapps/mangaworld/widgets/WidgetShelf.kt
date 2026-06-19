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
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
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
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

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
                label = "الرئيسية",
                onClick = AppLaunchIntents.home(context)
            )
            Spacer(GlanceModifier.width(8.dp))
            ShelfItem(
                label = "البحث",
                onClick = AppLaunchIntents.search(context)
            )
            Spacer(GlanceModifier.width(8.dp))
            ShelfItem(
                label = "المكتبة",
                onClick = AppLaunchIntents.home(context)
            )
            Spacer(GlanceModifier.width(8.dp))
            ShelfItem(
                label = "التنزيلات",
                onClick = AppLaunchIntents.downloads(context)
            )
        }
    }
}

@Composable
private fun ShelfItem(
    label: String,
    onClick: android.content.Intent
) {
    Column(
        modifier = GlanceModifier
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp
            )
        )
    }
}
