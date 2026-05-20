package com.exapps.mangaworld.presentation.reader

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class SmartCropTransformation : Transformation {
    override val cacheKey: String = "smart_crop_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        if (input.width < 16 || input.height < 16) return@withContext input

        val left = scanVertical(input, fromStart = true)
        val right = input.width - scanVertical(input, fromStart = false)
        val top = scanHorizontal(input, fromStart = true)
        val bottom = input.height - scanHorizontal(input, fromStart = false)

        val width = max(1, right - left)
        val height = max(1, bottom - top)
        if (width >= input.width && height >= input.height) return@withContext input
        Bitmap.createBitmap(input, left.coerceAtLeast(0), top.coerceAtLeast(0), min(width, input.width - left), min(height, input.height - top))
    }

    private fun scanVertical(bitmap: Bitmap, fromStart: Boolean): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = max(1, height / 60)
        val start = if (fromStart) 0 else width - 1
        val end = if (fromStart) width else -1
        val step = if (fromStart) 1 else -1
        var index = start
        while (index != end) {
            var matches = 0
            var total = 0
            var y = 0
            while (y < height) {
                val pixel = bitmap.getPixel(index, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (isMostlyMargin(r, g, b)) matches++
                total++
                y += sampleStep
            }
            if (total == 0 || matches.toFloat() / total < 0.94f) break
            index += step
        }
        return if (fromStart) index else width - 1 - index
    }

    private fun scanHorizontal(bitmap: Bitmap, fromStart: Boolean): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = max(1, width / 60)
        val start = if (fromStart) 0 else height - 1
        val end = if (fromStart) height else -1
        val step = if (fromStart) 1 else -1
        var index = start
        while (index != end) {
            var matches = 0
            var total = 0
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, index)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (isMostlyMargin(r, g, b)) matches++
                total++
                x += sampleStep
            }
            if (total == 0 || matches.toFloat() / total < 0.94f) break
            index += step
        }
        return if (fromStart) index else height - 1 - index
    }

    private fun isMostlyMargin(r: Int, g: Int, b: Int): Boolean {
        val bright = r > 242 && g > 242 && b > 242
        val dark = r < 18 && g < 18 && b < 18
        return bright || dark
    }
}
