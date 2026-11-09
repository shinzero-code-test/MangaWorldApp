package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.SmartCropScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * U-27(a) measure-only test: batched `getPixels` + subsampled scan.
 *
 * Generates synthetic pixel grids (white margin + gray content) and feeds
 * them to the pure [SmartCropScanner], counting row reads (one bulk
 * `getPixels` call each). The previous per-pixel implementation needed
 * ~60 `getPixel` calls per scanned row/column (thousands of calls/image);
 * the batched scanner must stay under [ROW_READ_THRESHOLD] rows and
 * complete well under the 250ms mid-tier gate. No Android APIs — runs on
 * the JVM in CI; device timing lives in the macrobenchmark spec.
 */
class SmartCropScannerTest {

    private val width = 1200
    private val height = 1600
    private val marginX = 60   // content starts at x=60, ends at x=1140
    private val marginY = 40   // content starts at y=40, ends at y=1560

    private val white = 0xFFFFFFFF.toInt()
    private val gray = 0xFF808080.toInt()

    private fun pixels(
        w: Int = width,
        h: Int = height,
        content: (x: Int, y: Int) -> Boolean,
    ): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = if (content(x, y)) gray else white
            }
        }
        return out
    }

    private fun reader(pixels: IntArray, w: Int, counter: IntArray?): (Int, IntArray) -> Unit =
        { y, out ->
            counter?.let { it[0]++ }
            System.arraycopy(pixels, y * w, out, 0, w)
        }

    @Test
    fun trimsKnownMargins_withFewRowReads() {
    @Test
    fun trimsKnownMargins_withFewRowReads() {
        // The §53 call previously omitted the required `content` lambda
        // (fake-profiler fix landed in CI, not in the initial commit).
        val px = pixels { x, y -> x >= marginX && x < width - marginX && y >= marginY && y < height - marginY }
        val reads = IntArray(1)
        val start = System.nanoTime()
        val bounds = SmartCropScanner.cropBounds(width, height, reader(px, width, reads))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("bounds=$bounds", bounds != null)
        val b = bounds!!
        // ±one sampling step (step = dim/60 ≈ 20px / 26px).
        assertTrue("left=${b.left}", abs(b.left - marginX) <= 26)
        assertTrue("right=${b.right}", abs(b.right - (width - marginX)) <= 26)
        assertTrue("top=${b.top}", abs(b.top - marginY) <= 26)
        assertTrue("bottom=${b.bottom}", abs(b.bottom - (height - marginY)) <= 26)
        assertTrue(
            "row reads=${reads[0]} exceeds threshold $ROW_READ_THRESHOLD",
            reads[0] < ROW_READ_THRESHOLD,
        )
        assertTrue("elapsedMs=$elapsedMs exceeds 250ms gate", elapsedMs < 250)
    }

    @Test
    fun blankPage_reportsNoCrop() {
        val px = pixels { _, _ -> false }
        val bounds = SmartCropScanner.cropBounds(width, height, reader(px, width, null))
        assertNull(bounds)
    }

    @Test
    fun fullContent_reportsNoCrop() {
        val px = pixels { _, _ -> true }
        val bounds = SmartCropScanner.cropBounds(width, height, reader(px, width, null))
        assertNull(bounds)
    }

    @Test
    fun darkMargins_areTrimmedToo() {
        val dark = 0xFF000000.toInt()
        val px = IntArray(width * height) { dark }
        for (y in marginY until height - marginY) {
            for (x in marginX until width - marginX) {
                px[y * width + x] = gray
            }
        }
        val bounds = SmartCropScanner.cropBounds(width, height, reader(px, width, null))
        assertTrue(bounds != null && bounds.left > 0 && bounds.top > 0)
    }

    private companion object {
        /**
         * Vertical sampling reads ceil(1600/26)≈62 rows; horizontal scans
         * break early (~41 rows each side for the 40px margins here), so a
         * healthy page needs ~150 bulk reads. The old per-pixel loop
         * needed ~60 getPixel calls per row scanned (worst case ~90k).
         */
        const val ROW_READ_THRESHOLD = 250
    }
}
