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
import com.exapps.mangaworld.R

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
    inverseSurface    = Color(0xFFE5E1E8),
    inverseOnSurface = Color(0xFF1C1B1F),
    inversePrimary    = MangaColors.PrimaryDim,
    surfaceTint       = MangaColors.PrimaryLight,
    surfaceContainerLow      = DarkThemeColors.Surface,
    surfaceContainerLowest   = DarkThemeColors.Background,
    surfaceBright            = DarkThemeColors.SurfaceHighest,
    surfaceDim               = DarkThemeColors.Background,
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
    inverseSurface    = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary    = Color(0xFFD8C0FF),
    surfaceTint       = Color(0xFF6833EA),
    surfaceContainerLow      = LightThemeColors.Surface,
    surfaceContainerLowest   = LightThemeColors.Background,
    surfaceBright            = LightThemeColors.SurfaceHighest,
    surfaceDim               = LightThemeColors.Background,
    surfaceContainer  = LightThemeColors.SurfaceContainer,
    surfaceContainerHigh = LightThemeColors.SurfaceHigh,
    surfaceContainerHighest = LightThemeColors.SurfaceHighest,
)

// ─── Typography ───────────────────────────────────────────────────────────────

/**
 * App type system (v8.1): Cairo for titles/headlines, IBM Plex Sans Arabic for
 * everything else. Both are bundled static instances downloaded from the
 * official Google Fonts repo — bundled (not GMS Downloadable Fonts) because the
 * app supports devices without Play Services.
 *
 * Cairo Bold was instanced from the variable font (slnt=0, wght=700).
 */
val CairoFontFamily = FontFamily(
    Font(R.font.cairo_bold, FontWeight.Bold)
)

val IbmPlexArabicFontFamily = FontFamily(
    Font(R.font.ibm_plex_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_arabic_semi_bold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_arabic_bold, FontWeight.Bold)
)

val MangaTypography = Typography(
    // Cairo — titles, headlines, display
    displayLarge  = TextStyle(fontFamily = CairoFontFamily, fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp, letterSpacing = 0.sp),
    displayMedium = TextStyle(fontFamily = CairoFontFamily, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp, letterSpacing = 0.sp),
    displaySmall  = TextStyle(fontFamily = CairoFontFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontFamily = CairoFontFamily, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium= TextStyle(fontFamily = CairoFontFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = CairoFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleLarge    = TextStyle(fontFamily = CairoFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium   = TextStyle(fontFamily = CairoFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    titleSmall    = TextStyle(fontFamily = CairoFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
    // IBM Plex Sans Arabic — body, labels, buttons, inputs
    bodyLarge     = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium    = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall     = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge    = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelMedium   = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall    = TextStyle(fontFamily = IbmPlexArabicFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

// ─── Shapes ───────────────────────────────────────────────────────────────────

object MangaCorner {
    val xs   = 4.dp
    val sm   = 6.dp
    val md   = 8.dp
    val lg   = 10.dp
    val xl   = 12.dp
    val xxl  = 14.dp
    val xxxl = 16.dp
    val huge = 18.dp
    val jumbo= 20.dp
    val pill = 100.dp
}

val MangaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(MangaCorner.xs),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(MangaCorner.md),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(MangaCorner.xl),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(MangaCorner.xxxl),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(MangaCorner.jumbo),
)

// ─── Spacing System ──────────────────────────────────────────────────────────

object Spacing {
    val xs  = 2.dp    // icon-to-text gaps, micro padding
    val sm  = 4.dp    // small internal gaps
    val md  = 8.dp    // common inner padding, chip padding
    val lg  = 12.dp   // section content padding, field spacing
    val xl  = 16.dp   // standard page horizontal padding
    val xxl = 20.dp   // setting section padding
    val xxxl = 24.dp   // section spacers, card padding
    val huge = 32.dp   // empty state padding, form horizontal padding
    val jumbo = 40.dp  // login/signup form top spacer
}

// ─── Animation System ────────────────────────────────────────────────────────

object Anim {
    /** Standard screen transition duration */
    const val screenEnter = 220
    const val screenExit  = 180

    /** Fast micro-interaction (ripple, scale, color change) */
    const val fast = 120

    /** Standard content transition (expand/collapse, fade) */
    const val medium = 220

    /** Slow content transition (page transition, shimmer) */
    const val slow = 400

    /** Shimmer loop duration */
    const val shimmer = 1200
}

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

    CompositionLocalProvider(
        // Default font for every Text that does not set one explicitly: Text()
        // merges its style into LocalTextStyle, so ad-hoc TextStyle(fontSize=…)
        // usages inherit IBM Plex instead of the platform default. Typography
        // styles carry their own families (Cairo/IBM Plex) and win on merge.
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = IbmPlexArabicFontFamily),
        LocalThemeColors provides themeColors
    ) {
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
