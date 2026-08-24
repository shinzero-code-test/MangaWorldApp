package com.exapps.mangaworld.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * A lightweight glass surface for dense media screens.
 *
 * It deliberately uses translucent composited layers instead of applying a backdrop blur to
 * every card. That preserves the frosted-glass visual language without turning a scrolling
 * screen full of cover images into a GPU-bound blur workload.
 */
@Composable
fun NeonGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    cornerRadius: Dp = 20.dp,
    glowColors: List<Color> = MangaColors.GradientPurpleCyan,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = mangaColors()
    // Hoisted so the gradient is allocated once per color-set, not per frame (S-review).
    val glowBrush = remember(glowColors) {
        Brush.radialGradient(
            colors = listOf(
                glowColors.first().copy(alpha = 0.28f),
                glowColors.last().copy(alpha = 0.12f),
                Color.Transparent
            )
        )
    }
    Box(
        modifier = modifier
            .drawBehind {
                val radius = cornerRadius.toPx()
                drawRoundRect(
                    brush = glowBrush,
                    center = Offset(size.width * 0.72f, size.height * 0.18f),
                    radius = maxOf(size.width, size.height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            .clip(shape)
            .background(colors.Surface.copy(alpha = 0.80f))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        glowColors.first().copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.26f),
                        glowColors.last().copy(alpha = 0.88f)
                    )
                ),
                shape = shape
            )
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
