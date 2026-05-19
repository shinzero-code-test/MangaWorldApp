package com.exapps.mangaworld.presentation.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberDominantColor(data: Any?): Color? {
    val context = LocalContext.current
    val state by produceState<Color?>(initialValue = null, key1 = data) {
        if (data == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(data)
                        .allowHardware(false)
                        .size(300, 300)
                        .build()
                )
                val bitmap = result.drawable?.toBitmap(width = 300, height = 300) ?: return@runCatching null
                bitmap.extractDominantColor()
            }.getOrNull()
        }
    }
    return state
}

private fun Bitmap.extractDominantColor(): Color? {
    val palette = Palette.from(this).clearFilters().generate()
    val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
    return swatch?.rgb?.let(::Color)?.let { color ->
        if (color.luminance() < 0.15f) color.copy(alpha = 0.85f) else color.copy(alpha = 0.75f)
    }
}
