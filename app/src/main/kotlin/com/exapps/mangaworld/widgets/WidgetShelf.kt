package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource


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

private data class ShelfAction(val icon: String, val label: String)

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
                text = stringResource(com.exapps.mangaworld.R.string.accessibility_back),
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
            ShelfButton(action = ShelfAction("🏠", stringResource(com.exapps.mangaworld.R.string.widget_shelf_home)), intent = AppLaunchIntents.home(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(action = ShelfAction("🔍", stringResource(com.exapps.mangaworld.R.string.widget_shelf_search)), intent = AppLaunchIntents.search(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(action = ShelfAction("📚", stringResource(com.exapps.mangaworld.R.string.widget_shelf_library)), intent = AppLaunchIntents.library(context))
            Spacer(GlanceModifier.width(6.dp))
            ShelfButton(action = ShelfAction("⬇️", stringResource(com.exapps.mangaworld.R.string.widget_shelf_downloads)), intent = AppLaunchIntents.downloads(context))
        }
    }
}

@Composable
private fun ShelfButton(action: ShelfAction, intent: android.content.Intent) {
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
            Text(action.icon, style = TextStyle(fontSize = 16.sp))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                action.label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

