package com.exapps.mangaworld.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.theme.mangaColors

/**
 * Glassmorphism toolkit (v8).
 *
 * A deliberately lightweight glass language: translucent composited layers plus a
 * soft radial glow — never a per-card backdrop blur, which would turn scrolling
 * media screens into GPU-bound workloads.
 */

/** Default glow pair for a given surface; callers may override with brand accents. */
@Composable
fun defaultGlowColors(): List<Color> = MangaColors.GradientPurpleCyan

/**
 * Draws the full glass treatment behind/around any content:
 * corner-anchored radial glow → translucent surface fill → gradient hairline border.
 */
fun Modifier.glassSurface(
    cornerRadius: Dp,
    shape: Shape,
    glowColors: List<Color>,
    baseAlpha: Float = 0.72f,
    glowIntensity: Float = 1f
): Modifier = composed {
    val colors = mangaColors()
    this
        .drawBehind {
            val radius = cornerRadius.toPx()
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColors.first().copy(alpha = 0.26f * glowIntensity),
                        glowColors.last().copy(alpha = 0.10f * glowIntensity),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.78f, size.height * 0.12f),
                    radius = maxOf(size.width, size.height) * 1.15f
                ),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
        .clip(shape)
        .background(colors.Surface.copy(alpha = baseAlpha))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    glowColors.first().copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.18f),
                    glowColors.last().copy(alpha = 0.55f)
                )
            ),
            shape = shape
        )
}

/** Soft outer halo for primary actions (buttons, active chips). Drawn behind content. */
fun Modifier.glowHalo(
    cornerRadius: Dp,
    color: Color,
    intensity: Float = 0.45f
): Modifier = composed {
    this.drawBehind {
        val radius = cornerRadius.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = intensity), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = maxOf(size.width, size.height) * 0.9f
            ),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/**
 * Breathing glow for hero surfaces (featured card, read-now button). The pulse is
 * slow and subtle so it reads as ambient light rather than animation noise.
 */
fun Modifier.pulsingGlow(
    cornerRadius: Dp,
    glowColors: List<Color>
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "glass_pulse")
    val phase by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glass_pulse_phase"
    )
    this.drawBehind {
        val radius = cornerRadius.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColors.first().copy(alpha = 0.20f * phase),
                    glowColors.last().copy(alpha = 0.08f * phase),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.75f, size.height * 0.15f),
                radius = maxOf(size.width, size.height) * 1.2f
            ),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/**
 * A lightweight glass surface for dense media screens.
 * Retained for existing call sites; new code should prefer [Modifier.glassSurface].
 */
@Composable
fun NeonGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    cornerRadius: Dp = 20.dp,
    glowColors: List<Color> = MangaColors.GradientPurpleCyan,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.glassSurface(cornerRadius, shape, glowColors, baseAlpha = 0.80f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)
                    )
                )
        )
        content()
    }
}

/**
 * Drop-in replacement for Material3 Card with the app-wide glass treatment (v8).
 * Optional onClick makes it a clickable surface; otherwise it is static.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    cornerRadius: Dp = 14.dp,
    glowColors: List<Color> = MangaColors.GradientPurpleCyan,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clip(shape).clickable(onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .glassSurface(cornerRadius, shape, glowColors, baseAlpha = 0.78f)
            .then(clickableModifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                    )
                )
        )
        content()
    }
}

/**
 * The app's single bottom-sheet style (v8): translucent glass container,
 * 28dp rounded top, built-in drag handle, edge-to-edge content padding.
 * Every ModalBottomSheet in the app must go through this so sheets share
 * one visual language instead of raw Material defaults.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = mangaColors()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.Surface.copy(alpha = 0.96f),
        contentColor = colors.OnSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MangaColors.Primary.copy(alpha = 0.7f),
                                MangaColors.Cyan.copy(alpha = 0.7f)
                            )
                        )
                    )
            )
        },
        content = content
    )
}
