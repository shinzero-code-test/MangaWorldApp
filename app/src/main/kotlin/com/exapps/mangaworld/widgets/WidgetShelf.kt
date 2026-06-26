package com.exapps.mangaworld.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.action.clickable
import androidx.glance.color.ColorProvider
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
            Box(
                modifier = GlanceModifier
                    .padding(4.dp)
                    .background(ColorProvider(day = Color(0x1AFFFFFF), night = Color(0x33FFFFFF)))
                    .clickable(actionStartActivity(AppLaunchIntents.home(context)))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("🏠 الرئيسية", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)) }

            Spacer(GlanceModifier.width(6.dp))

            Box(
                modifier = GlanceModifier
                    .padding(4.dp)
                    .background(ColorProvider(day = Color(0x1AFFFFFF), night = Color(0x33FFFFFF)))
                    .clickable(actionStartActivity(AppLaunchIntents.search(context)))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("🔍 البحث", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)) }

            Spacer(GlanceModifier.width(6.dp))

            Box(
                modifier = GlanceModifier
                    .padding(4.dp)
                    .background(ColorProvider(day = Color(0x1AFFFFFF), night = Color(0x33FFFFFF)))
                    .clickable(actionStartActivity(AppLaunchIntents.library(context)))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("📚 المكتبة", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)) }

            Spacer(GlanceModifier.width(6.dp))

            Box(
                modifier = GlanceModifier
                    .padding(4.dp)
                    .background(ColorProvider(day = Color(0x1AFFFFFF), night = Color(0x33FFFFFF)))
                    .clickable(actionStartActivity(AppLaunchIntents.downloads(context)))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("⬇️ التنزيلات", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)) }
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
            .background(ColorProvider(day = Color(0x1AFFFFFF), night = Color(0x33FFFFFF)))
            .clickable(onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
