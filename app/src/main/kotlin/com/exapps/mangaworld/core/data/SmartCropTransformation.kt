package com.exapps.mangaworld.core.data

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Smart-crop transformation (trims bright/dark edge margins around page
 * content). Moved from `presentation.reader` so the shared reader-page
 * request builder (`ReaderImageRequest`, same package) can reference it
 * without a core→presentation layering inversion.
 *
 * U-27(a): batched `getPixels` + subsampled scan. The previous
 * implementation called `bitmap.getPixel(x, y)` once per sampled pixel
 * (up to ~60 calls per scanned row/column on margin-heavy pages). The
 * scanner below bulk-reads each sampled row with one `getPixels` call and
 * evaluates sampled positions from the resulting IntArray. Same margin
 * threshold (0.94) and same sampling grids (`max(1, dim/60)`), so crops
 * are identical to the per-pixel version — only the I/O is batched. The
 * `cacheKey` is therefore intentionally unchanged (`smart_crop_v1`):
 * previously cached crops stay valid.
 *
 * Input bitmaps are decode-capped upstream (`ReaderImageConfig`, 4096px
 * max side) so the scan never sees multi-megapixel webtoon strips.
 */
class SmartCropTransformation : Transformation {
    override val cacheKey: String = "smart_crop_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        if (input.width < 16 || input.height < 16) return@withContext input

        val bounds = SmartCropScanner.cropBounds(input.width, input.height) { y, out ->
            input.getPixels(out, 0, input.width, 0, y, input.width, 1)
        } ?: return@withContext input

        // Coerce into the bitmap rect (belt-and-braces; the scanner already
        // returns in-bounds exclusive bounds).
        val left = bounds.left.coerceIn(0, input.width - 1)
        val top = bounds.top.coerceIn(0, input.height - 1)
        val width = (bounds.right - left).coerceIn(1, input.width - left)
        val height = (bounds.bottom - top).coerceIn(1, input.height - top)
        Bitmap.createBitmap(input, left, top, width, height)
    }
}

/**
 * Pure margin scanner: operates only on IntArray rows (no Android types),
 * so JVM unit tests can assert sample counts and timing without
 * Robolectric or an emulator. Production passes rows bulk-read via
 * `Bitmap.getPixels` (one call per sampled row instead of one per pixel).
 */
internal object SmartCropScanner {
    /** Exclusive crop bounds `[left, right) x [top, bottom)`; null = no crop. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Fraction of sampled row/column pixels that must read as margin. */
    internal const val MARGIN_THRESHOLD = 0.94f

    fun cropBounds(
        width: Int,
        height: Int,
        readRow: (y: Int, out: IntArray) -> Unit,
    ): Bounds? {
        if (width < 16 || height < 16) return null

        val row = IntArray(width)

        // Vertical edges (left/right): sample every `stepY`-th row in bulk
        // and count margin hits per column.
        val stepY = max(1, height / 60)
        val sampledRows = ((height + stepY - 1) / stepY).toFloat()
        val colMatches = IntArray(width)
        for (y in 0 until height step stepY) {
            readRow(y, row)
            for (x in row.indices) {
                if (isMarginPixel(row[x])) colMatches[x]++
            }
        }

        val left = (0 until width).firstOrNull {
            colMatches[it].toFloat() / sampledRows < MARGIN_THRESHOLD
        } ?: width
        val right = (width - 1 downTo 0).firstOrNull {
            colMatches[it].toFloat() / sampledRows < MARGIN_THRESHOLD
        }?.let { it + 1 } ?: 0
        if (right <= left) return null

        // Horizontal edges (top/bottom): batched per-row reads with an
        // early break once content starts (typical pages break within a
        // few rows; a pathological blank page scans the full height but
        // still costs one bulk read per row, never one call per pixel).
        val stepX = max(1, width / 60)

        fun rowIsMargin(y: Int): Boolean {
            readRow(y, row)
            var matches = 0
            var total = 0
            var x = 0
            while (x < width) {
                if (isMarginPixel(row[x])) matches++
                total++
                x += stepX
            }
            return total > 0 && matches.toFloat() / total >= MARGIN_THRESHOLD
        }

        var top = 0
        while (top < height && rowIsMargin(top)) top++
        var bottom = height
        while (bottom > 0 && rowIsMargin(bottom - 1)) bottom--
        if (bottom <= top) return null

        if (left <= 0 && right >= width && top <= 0 && bottom >= height) return null
        return Bounds(left, top, right, bottom)
    }

    internal fun isMarginPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val bright = r > 242 && g > 242 && b > 242
        val dark = r < 18 && g < 18 && b < 18
        return bright || dark
    }
}
