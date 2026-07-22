package com.exapps.mangaworld.widgets
import com.exapps.mangaworld.R

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.core.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import androidx.glance.LocalContext

class WidgetShelf : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 40.dp), DpSize(240.dp, 50.dp), DpSize(320.dp, 60.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = entryPoint.widgetSettingsManager()
        provideContent {
            MangaWidgetTheme(context, settings.getWidgetTheme()) {
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
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier
                    .width(4.dp)
                    .height(14.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(2.dp)
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = LocalContext.current.getString(R.string.accessibility_back),
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            )
        }
        Spacer(GlanceModifier.height(10.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShelfButton(label = context.getString(R.string.widget_shelf_home), iconRes = R.drawable.ic_shortcut_search, intent = AppLaunchIntents.home(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(label = context.getString(R.string.widget_shelf_search), iconRes = R.drawable.ic_shortcut_search, intent = AppLaunchIntents.search(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(label = context.getString(R.string.widget_shelf_library), iconRes = R.drawable.ic_shortcut_downloads, intent = AppLaunchIntents.library(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(label = context.getString(R.string.widget_shelf_downloads), iconRes = R.drawable.ic_shortcut_downloads, intent = AppLaunchIntents.downloads(context))
        }
    }
}

@Composable
private fun ShelfButton(label: String, iconRes: Int, intent: android.content.Intent) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(14.dp)
            .clickable(actionStartActivity(intent))
            .padding(horizontal = 6.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            androidx.glance.Image(
                provider = ImageProvider(iconRes),
                contentDescription = label,
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}
