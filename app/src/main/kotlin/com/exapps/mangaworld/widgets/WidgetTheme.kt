package com.exapps.mangaworld.widgets

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.material3.ColorProviders
import androidx.glance.material3.GlanceTheme

internal fun widgetColorProviders(context: Context): ColorProviders {
    val light = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEADDFF),
            surface = Color(0xFFFFFBFE),
            surfaceVariant = Color(0xFFE7E0EC),
            onSurface = Color(0xFF1C1B1F),
            onSurfaceVariant = Color(0xFF49454F)
        )
    }
    val dark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            surface = Color(0xFF141218),
            surfaceVariant = Color(0xFF49454F),
            onSurface = Color(0xFFE6E1E5),
            onSurfaceVariant = Color(0xFFCAC4D0)
        )
    }
    return ColorProviders(light, dark)
}

@Composable
internal fun MangaWidgetTheme(context: Context, content: @Composable () -> Unit) {
    GlanceTheme(colors = widgetColorProviders(context)) {
        content()
    }
}
