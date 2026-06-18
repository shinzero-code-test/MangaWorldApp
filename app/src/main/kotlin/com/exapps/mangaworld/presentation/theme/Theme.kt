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

// ─── Brand Colors ─────────────────────────────────────────────────────────────

object MangaColors {
    // Backgrounds
    val Background       = Color(0xFF0F0F17)
    val Surface          = Color(0xFF13131B)
    val SurfaceContainer = Color(0xFF1A1A26)
    val SurfaceHigh      = Color(0xFF292932)
    val SurfaceHighest   = Color(0xFF34343D)
    val CardBg           = Color(0xFF1B1B23)

    // Accents
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

    // Text
    val OnSurface        = Color(0xFFE4E1ED)
    val OnSurfaceVariant = Color(0xFF9494B8)
    val Muted            = Color(0xFF666680)
    val MutedLight       = Color(0xFFAAAAACC)

    // Borders
    val OutlineVariant   = Color(0xFF494455)
    val DividerColor     = Color(0xFF1A1A2E)

    // Semantic
    val NewBadge         = Pink
    val PaidBadge        = Yellow
    val ReadColor        = Green
    val OngoingColor     = Green
    val CompletedColor   = Color(0xFF8888AA)
    val HiatusColor      = Orange

    // Gradient helpers
    val GradientPurpleCyan = listOf(Primary, Cyan)
    val GlowPurple       = Color(0x4D7C4DFF)
    val GlowCyan         = Color(0x4D00E5FF)
}

// ─── Material3 Color Scheme ───────────────────────────────────────────────────

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
    background        = MangaColors.Background,
    onBackground      = MangaColors.OnSurface,
    surface           = MangaColors.Surface,
    onSurface         = MangaColors.OnSurface,
    surfaceVariant    = MangaColors.SurfaceHighest,
    onSurfaceVariant  = MangaColors.OnSurfaceVariant,
    outline           = Color(0xFF948EA1),
    outlineVariant    = MangaColors.OutlineVariant,
    surfaceContainer  = MangaColors.SurfaceContainer,
    surfaceContainerHigh = MangaColors.SurfaceHigh,
    surfaceContainerHighest = MangaColors.SurfaceHighest,
)

private val LightColorScheme = lightColorScheme(
    primary           = Color(0xFF6833EA),
    onPrimary         = Color(0xFFFFFFFF),
    primaryContainer  = Color(0xFFEDE7FF),
    onPrimaryContainer= Color(0xFF20005F),
    background        = Color(0xFFFBF8FF),
    onBackground      = Color(0xFF1C1B1F),
    surface           = Color(0xFFFBF8FF),
    onSurface         = Color(0xFF1C1B1F),
    surfaceVariant    = Color(0xFFE7E0EC),
    onSurfaceVariant  = Color(0xFF49454F),
)

// ─── Typography ───────────────────────────────────────────────────────────────

val MangaTypography = Typography(
    displayLarge  = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp, letterSpacing = (-0.02).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium= TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleLarge    = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium   = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge     = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall     = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = MangaTypography,
        shapes      = MangaShapes,
        content     = content
    )
}
