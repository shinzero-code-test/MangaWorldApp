package com.exapps.mangaworld.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Brand Colors (hardcoded accents — never change with theme) ──────────────

object MangaColors {
    // Accents — always the same regardless of dynamic colors
    val Primary          = Color(0xFF7C4DFF)   // Purple
    val PrimaryLight     = Color(0xFFCDBDFF)
    val PrimaryDim       = Color(0xFF4F00D0)
    val Cyan             = Color(0xFF00E5FF)
    val CyanDim          = Color(0xFF00A3B4)
    val Pink             = Color(0xFFFF4081)
    val Green            = Color(0xFF4CAF50)
    val Yellow           = Color(0xFFFFD700)
    val Orange           = Color(0xFFFF9800)
    val Error            = Color(0xFFCF6679)

    // Gradient helpers
    val GradientPurpleCyan = listOf(Primary, Cyan)
    val GlowPurple       = Color(0x4D7C4DFF)
    val GlowCyan         = Color(0x4D00E5FF)

    // Semantic
    val NewBadge         = Pink
    val PaidBadge        = Yellow
    val ReadColor        = Green
    val OngoingColor     = Green
    val CompletedColor   = Color(0xFF8888AA)
    val HiatusColor      = Orange

    // ─── DEPRECATED: Theme-dependent static vals ──────────────────────────────
    // These always resolve to DarkThemeColors. Use themeColors() or mangaColors() instead.
    @Deprecated("Use themeColors().Background or mangaColors().Background", ReplaceWith("themeColors().Background"))
    val Background       = DarkThemeColors.Background
    @Deprecated("Use themeColors().Surface or mangaColors().Surface", ReplaceWith("themeColors().Surface"))
    val Surface          = DarkThemeColors.Surface
    @Deprecated("Use themeColors().SurfaceContainer or mangaColors().SurfaceContainer", ReplaceWith("themeColors().SurfaceContainer"))
    val SurfaceContainer = DarkThemeColors.SurfaceContainer
    @Deprecated("Use themeColors().SurfaceHigh or mangaColors().SurfaceHigh", ReplaceWith("themeColors().SurfaceHigh"))
    val SurfaceHigh      = DarkThemeColors.SurfaceHigh
    @Deprecated("Use themeColors().SurfaceHighest or mangaColors().SurfaceHighest", ReplaceWith("themeColors().SurfaceHighest"))
    val SurfaceHighest   = DarkThemeColors.SurfaceHighest
    @Deprecated("Use themeColors().CardBg or mangaColors().CardBg", ReplaceWith("themeColors().CardBg"))
    val CardBg           = DarkThemeColors.CardBg
    @Deprecated("Use themeColors().OnSurface or mangaColors().OnSurface", ReplaceWith("themeColors().OnSurface"))
    val OnSurface        = DarkThemeColors.OnSurface
    @Deprecated("Use themeColors().OnSurfaceVariant or mangaColors().OnSurfaceVariant", ReplaceWith("themeColors().OnSurfaceVariant"))
    val OnSurfaceVariant = DarkThemeColors.OnSurfaceVariant
    @Deprecated("Use themeColors().Muted or mangaColors().Muted", ReplaceWith("themeColors().Muted"))
    val Muted            = DarkThemeColors.Muted
    @Deprecated("Use themeColors().MutedLight or mangaColors().MutedLight", ReplaceWith("themeColors().MutedLight"))
    val MutedLight       = DarkThemeColors.MutedLight
    @Deprecated("Use themeColors().OutlineVariant or mangaColors().OutlineVariant", ReplaceWith("themeColors().OutlineVariant"))
    val OutlineVariant   = DarkThemeColors.OutlineVariant
    @Deprecated("Use themeColors().DividerColor or mangaColors().DividerColor", ReplaceWith("themeColors().DividerColor"))
    val DividerColor     = DarkThemeColors.DividerColor
}

/**
 * Theme-aware colors — these adapt to dynamic colors (Material You).
 * Use these instead of hardcoded MangaColors for backgrounds, surfaces, and text.
 */
@Immutable
data class ThemeColors(
    val Background: Color,
    val Surface: Color,
    val SurfaceContainer: Color,
    val SurfaceHigh: Color,
    val SurfaceHighest: Color,
    val CardBg: Color,
    val OnSurface: Color,
    val OnSurfaceVariant: Color,
    val Muted: Color,
    val MutedLight: Color,
    val OutlineVariant: Color,
    val DividerColor: Color
)

private val DarkThemeColors = ThemeColors(
    Background       = Color(0xFF0F0F17),
    Surface          = Color(0xFF13131B),
    SurfaceContainer = Color(0xFF1A1A26),
    SurfaceHigh      = Color(0xFF292932),
    SurfaceHighest   = Color(0xFF34343D),
    CardBg           = Color(0xFF1B1B23),
    OnSurface        = Color(0xFFE4E1ED),
    OnSurfaceVariant = Color(0xFF9494B8),
    Muted            = Color(0xFF666680),
    MutedLight       = Color(0xFFAAAACC),
    OutlineVariant   = Color(0xFF494455),
    DividerColor     = Color(0xFF1A1A2E)
)

private val LightThemeColors = ThemeColors(
    Background       = Color(0xFFFBF8FF),
    Surface          = Color(0xFFFFFFFF),
    SurfaceContainer = Color(0xFFF4F0FA),
    SurfaceHigh      = Color(0xFFEAE5F0),
    SurfaceHighest   = Color(0xFFDDD8E3),
    CardBg           = Color(0xFFF8F5FC),
    OnSurface        = Color(0xFF1C1B1F),
    OnSurfaceVariant = Color(0xFF49454F),
    Muted            = Color(0xFF8888AA),
    MutedLight       = Color(0xFFAAAABC),
    OutlineVariant   = Color(0xFFCAC4D0),
    DividerColor     = Color(0xFFE0DCE8)
)

val LocalThemeColors = staticCompositionLocalOf { DarkThemeColors }

// ─── Material3 Color Scheme ───────────────────────────────────────────────────

/**
 * Material3 ColorScheme — used by MaterialTheme(colorScheme = ...).
 * References DarkThemeColors/LightThemeColors directly (not the deprecated MangaColors static vals).
 * For custom composables, prefer themeColors() or mangaColors() which read from LocalThemeColors.
 */
private val DarkColorScheme = darkColorScheme(
    primary           = MangaColors.PrimaryLight,
    onPrimary         = Color(0xFF370096),
    primaryContainer  = MangaColors.Primary,
    onPrimaryContainer= Color(0xFFFCF6FF),
    secondary         = Color(0xFFBDF4FF),
    onSecondary       = Color(0xFF00363D),
    secondaryContainer= MangaColors.Cyan,
    onSecondaryContainer = Color(0xFF00616D),
    tertiary          = Color(0xFFFFB1C1),
    onTertiary        = Color(0xFF66002A),
    tertiaryContainer = MangaColors.Pink,
    error             = Color(0xFFFFB4AB),
    background        = DarkThemeColors.Background,
    onBackground      = DarkThemeColors.OnSurface,
    surface           = DarkThemeColors.Surface,
    onSurface         = DarkThemeColors.OnSurface,
    surfaceVariant    = DarkThemeColors.SurfaceHigh,
    onSurfaceVariant  = DarkThemeColors.OnSurfaceVariant,
    outline           = Color(0xFF948EA1),
    outlineVariant    = DarkThemeColors.OutlineVariant,
    surfaceContainer  = DarkThemeColors.SurfaceContainer,
    surfaceContainerHigh = DarkThemeColors.SurfaceHigh,
    surfaceContainerHighest = DarkThemeColors.SurfaceHighest,
)

private val LightColorScheme = lightColorScheme(
    primary           = Color(0xFF6833EA),
    onPrimary         = Color(0xFFFFFFFF),
    primaryContainer  = Color(0xFFEDE7FF),
    onPrimaryContainer= Color(0xFF20005F),
    secondary         = Color(0xFF006B73),
    onSecondary       = Color(0xFFFFFFFF),
    secondaryContainer= Color(0xFFB2EEF8),
    onSecondaryContainer = Color(0xFF002025),
    tertiary          = Color(0xFF8C3256),
    onTertiary        = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    error             = Color(0xFFBA1A1A),
    onError           = Color(0xFFFFFFFF),
    background        = LightThemeColors.Background,
    onBackground      = LightThemeColors.OnSurface,
    surface           = LightThemeColors.Surface,
    onSurface         = LightThemeColors.OnSurface,
    surfaceVariant    = LightThemeColors.SurfaceHigh,
    onSurfaceVariant  = LightThemeColors.OnSurfaceVariant,
    outline           = Color(0xFF7A7581),
    outlineVariant    = LightThemeColors.OutlineVariant,
    surfaceContainer  = LightThemeColors.SurfaceContainer,
    surfaceContainerHigh = LightThemeColors.SurfaceHigh,
    surfaceContainerHighest = LightThemeColors.SurfaceHighest,
)

// ─── Typography ───────────────────────────────────────────────────────────────

val MangaTypography = Typography(
    displayLarge  = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp, letterSpacing = (-0.02).sp),
    displayMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp, letterSpacing = 0.sp),
    displaySmall  = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium= TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge    = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium   = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleSmall    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge     = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall     = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelMedium   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall    = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

// ─── Shapes ───────────────────────────────────────────────────────────────────

val MangaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

// ─── Theme Composable ─────────────────────────────────────────────────────────

@Composable
fun MangaWorldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColors: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val themeColors = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            DarkThemeColors.copy(
                Background = colorScheme.background,
                Surface = colorScheme.surface,
                SurfaceContainer = colorScheme.surfaceContainer,
                SurfaceHigh = colorScheme.surfaceContainerHigh,
                SurfaceHighest = colorScheme.surfaceContainerHighest,
                OnSurface = colorScheme.onSurface,
                OnSurfaceVariant = colorScheme.onSurfaceVariant
            )
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            LightThemeColors.copy(
                Background = colorScheme.background,
                Surface = colorScheme.surface,
                SurfaceContainer = colorScheme.surfaceContainer,
                SurfaceHigh = colorScheme.surfaceContainerHigh,
                SurfaceHighest = colorScheme.surfaceContainerHighest,
                OnSurface = colorScheme.onSurface,
                OnSurfaceVariant = colorScheme.onSurfaceVariant
            )
        darkTheme -> DarkThemeColors
        else -> LightThemeColors
    }

    CompositionLocalProvider(LocalThemeColors provides themeColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = MangaTypography,
            shapes      = MangaShapes,
            content     = content
        )
    }
}

/** Access theme-aware colors from any composable */
@Composable
fun themeColors(): ThemeColors = LocalThemeColors.current

/**
 * Theme-aware replacement for the static MangaColors surface/text vals.
 * Provides the same color roles as MangaColors.Background, MangaColors.Surface, etc.
 * but adapts to the current theme (light/dark/dynamic).
 *
 * Usage: val colors = mangaColors()
 *        Box(Modifier.background(colors.background))
 */
@Composable
fun mangaColors() = LocalThemeColors.current
