package com.exapps.mangaworld.widgets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider

/**
 * MangaWorld brand palette — matches the in-app "Dark Blood" theme shown
 * in the widget preview mockup (deep black surfaces, blood-red accents).
 *
 * NOTE: We intentionally do NOT use dynamicLightColorScheme/dynamicDarkColorScheme
 * (Material You). Those pull colors from the user's wallpaper and would silently
 * override the MangaWorld brand identity on Android 12+. Brand colors are fixed
 * and only flex with WidgetTheme (system/light/dark/monochrome) chosen in settings.
 */
private object MangaColors {
    // Core brand red — same red as the logo splash / primary buttons in the mock.
    val BloodRed = Color(0xFFE21B1B)
    val BloodRedDeep = Color(0xFFB3151A)
    val BloodRedSoft = Color(0xFFFF4B4B)

    // Dark theme surfaces — near-black with a faint red-tinted card surface.
    val DarkBackground = Color(0xFF0D0B0C)
    val DarkSurface = Color(0xFF171315)
    val DarkSurfaceVariant = Color(0xFF241D1F)
    val DarkOutline = Color(0x33E21B1B) // faint red hairline border seen around cards
    val DarkOnSurface = Color(0xFFF5EEEE)
    val DarkOnSurfaceVariant = Color(0xFFB9ACAE)

    // Light theme surfaces — kept warm/neutral so the red accent still reads as "MangaWorld".
    val LightBackground = Color(0xFFFAF7F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFF1E9E9)
    val LightOutline = Color(0x1AE21B1B)
    val LightOnSurface = Color(0xFF1C1414)
    val LightOnSurfaceVariant = Color(0xFF5C4E4F)

    // Monochrome — pure grayscale, red swapped for a near-white accent so the
    // "أحادي اللون" preview swatch (grey pill) in the mock is honored.
    val MonoAccent = Color(0xFFB0B0B0)
    val MonoBackground = Color(0xFF121212)
    val MonoSurface = Color(0xFF1B1B1B)
    val MonoSurfaceVariant = Color(0xFF2A2A2A)
    val MonoOnSurface = Color(0xFFF2F2F2)
    val MonoOnSurfaceVariant = Color(0xFFAFAFAF)
}

private data class WidgetPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
    val onError: Color
)

private fun darkPalette(accent: Color = MangaColors.BloodRed) = WidgetPalette(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = MangaColors.BloodRedDeep,
    onPrimaryContainer = Color.White,
    background = MangaColors.DarkBackground,
    onBackground = MangaColors.DarkOnSurface,
    surface = MangaColors.DarkSurface,
    onSurface = MangaColors.DarkOnSurface,
    surfaceVariant = MangaColors.DarkSurfaceVariant,
    onSurfaceVariant = MangaColors.DarkOnSurfaceVariant,
    outline = MangaColors.DarkOutline,
    error = MangaColors.BloodRedSoft,
    onError = Color.White
)

private fun lightPalette(accent: Color = MangaColors.BloodRed) = WidgetPalette(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = MangaColors.BloodRedDeep,
    onPrimaryContainer = Color.White,
    background = MangaColors.LightBackground,
    onBackground = MangaColors.LightOnSurface,
    surface = MangaColors.LightSurface,
    onSurface = MangaColors.LightOnSurface,
    surfaceVariant = MangaColors.LightSurfaceVariant,
    onSurfaceVariant = MangaColors.LightOnSurfaceVariant,
    outline = MangaColors.LightOutline,
    error = MangaColors.BloodRedDeep,
    onError = Color.White
)

private fun monochromePalette() = WidgetPalette(
    primary = MangaColors.MonoAccent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color.White,
    background = MangaColors.MonoBackground,
    onBackground = MangaColors.MonoOnSurface,
    surface = MangaColors.MonoSurface,
    onSurface = MangaColors.MonoOnSurface,
    surfaceVariant = MangaColors.MonoSurfaceVariant,
    onSurfaceVariant = MangaColors.MonoOnSurfaceVariant,
    outline = Color(0x33FFFFFF),
    error = Color(0xFFCF6679),
    onError = Color.Black
)

private fun isSystemInDarkMode(context: Context): Boolean {
    val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mode == Configuration.UI_MODE_NIGHT_YES
}

internal fun widgetColorProviders(context: Context, theme: WidgetTheme): ColorProviders {
    val palette = when (theme) {
        WidgetTheme.SYSTEM -> if (isSystemInDarkMode(context)) darkPalette() else lightPalette()
        WidgetTheme.LIGHT -> lightPalette()
        WidgetTheme.DARK -> darkPalette()
        WidgetTheme.MONOCHROME -> monochromePalette()
    }

    fun cp(color: Color) = ColorProvider(color)

    return colorProviders(
        primary = cp(palette.primary),
        onPrimary = cp(palette.onPrimary),
        primaryContainer = cp(palette.primaryContainer),
        onPrimaryContainer = cp(palette.onPrimaryContainer),
        secondary = cp(palette.primary),
        onSecondary = cp(palette.onPrimary),
        secondaryContainer = cp(palette.surfaceVariant),
        onSecondaryContainer = cp(palette.onSurfaceVariant),
        tertiary = cp(palette.primary),
        onTertiary = cp(palette.onPrimary),
        tertiaryContainer = cp(palette.primaryContainer),
        onTertiaryContainer = cp(palette.onPrimaryContainer),
        error = cp(palette.error),
        errorContainer = cp(palette.primaryContainer),
        onError = cp(palette.onError),
        onErrorContainer = cp(palette.onPrimary),
        background = cp(palette.background),
        onBackground = cp(palette.onBackground),
        surface = cp(palette.surface),
        onSurface = cp(palette.onSurface),
        surfaceVariant = cp(palette.surfaceVariant),
        onSurfaceVariant = cp(palette.onSurfaceVariant),
        outline = cp(palette.outline),
        inverseOnSurface = cp(palette.background),
        inverseSurface = cp(palette.onSurface),
        inversePrimary = cp(palette.onPrimary)
    )
}

/**
 * Wraps widget content with the MangaWorld brand theme, honoring the user's
 * WidgetTheme preference (system/light/dark/monochrome) from WidgetSettingsManager.
 */
@Composable
internal fun MangaWidgetTheme(
    context: Context,
    theme: WidgetTheme = WidgetTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    GlanceTheme(colors = widgetColorProviders(context, theme)) {
        content()
    }
}
