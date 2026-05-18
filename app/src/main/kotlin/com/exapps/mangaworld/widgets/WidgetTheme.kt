package com.exapps.mangaworld.widgets

import android.content.Context
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
    fun cp(day: Color, night: Color) = ColorProvider(day = day, night = night)

    return colorProviders(
        primary = cp(light.primary, dark.primary),
        onPrimary = cp(light.onPrimary, dark.onPrimary),
        primaryContainer = cp(light.primaryContainer, dark.primaryContainer),
        onPrimaryContainer = cp(light.onPrimaryContainer, dark.onPrimaryContainer),
        secondary = cp(light.secondary, dark.secondary),
        onSecondary = cp(light.onSecondary, dark.onSecondary),
        secondaryContainer = cp(light.secondaryContainer, dark.secondaryContainer),
        onSecondaryContainer = cp(light.onSecondaryContainer, dark.onSecondaryContainer),
        tertiary = cp(light.tertiary, dark.tertiary),
        onTertiary = cp(light.onTertiary, dark.onTertiary),
        tertiaryContainer = cp(light.tertiaryContainer, dark.tertiaryContainer),
        onTertiaryContainer = cp(light.onTertiaryContainer, dark.onTertiaryContainer),
        error = cp(light.error, dark.error),
        errorContainer = cp(light.errorContainer, dark.errorContainer),
        onError = cp(light.onError, dark.onError),
        onErrorContainer = cp(light.onErrorContainer, dark.onErrorContainer),
        background = cp(light.background, dark.background),
        onBackground = cp(light.onBackground, dark.onBackground),
        surface = cp(light.surface, dark.surface),
        onSurface = cp(light.onSurface, dark.onSurface),
        surfaceVariant = cp(light.surfaceVariant, dark.surfaceVariant),
        onSurfaceVariant = cp(light.onSurfaceVariant, dark.onSurfaceVariant),
        outline = cp(light.outline, dark.outline),
        inverseOnSurface = cp(light.inverseOnSurface, dark.inverseOnSurface),
        inverseSurface = cp(light.inverseSurface, dark.inverseSurface),
        inversePrimary = cp(light.inversePrimary, dark.inversePrimary)
    )
}

@Composable
internal fun MangaWidgetTheme(context: Context, content: @Composable () -> Unit) {
    GlanceTheme(colors = widgetColorProviders(context)) {
        content()
    }
}
