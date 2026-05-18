package com.exapps.mangaworld.widgets

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider

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
    val active = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
        dark
    } else {
        light
    }

    fun cp(color: Color) = ColorProvider(color)

    return colorProviders(
        primary = cp(active.primary),
        onPrimary = cp(active.onPrimary),
        primaryContainer = cp(active.primaryContainer),
        onPrimaryContainer = cp(active.onPrimaryContainer),
        secondary = cp(active.secondary),
        onSecondary = cp(active.onSecondary),
        secondaryContainer = cp(active.secondaryContainer),
        onSecondaryContainer = cp(active.onSecondaryContainer),
        tertiary = cp(active.tertiary),
        onTertiary = cp(active.onTertiary),
        tertiaryContainer = cp(active.tertiaryContainer),
        onTertiaryContainer = cp(active.onTertiaryContainer),
        error = cp(active.error),
        errorContainer = cp(active.errorContainer),
        onError = cp(active.onError),
        onErrorContainer = cp(active.onErrorContainer),
        background = cp(active.background),
        onBackground = cp(active.onBackground),
        surface = cp(active.surface),
        onSurface = cp(active.onSurface),
        surfaceVariant = cp(active.surfaceVariant),
        onSurfaceVariant = cp(active.onSurfaceVariant),
        outline = cp(active.outline),
        inverseOnSurface = cp(active.inverseOnSurface),
        inverseSurface = cp(active.inverseSurface),
        inversePrimary = cp(active.inversePrimary)
    )
}

@Composable
internal fun MangaWidgetTheme(context: Context, content: @Composable () -> Unit) {
    GlanceTheme(colors = widgetColorProviders(context)) {
        content()
    }
}
