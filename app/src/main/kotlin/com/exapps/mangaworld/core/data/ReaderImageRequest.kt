package com.exapps.mangaworld.core.data

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.ReaderImageFilter

/**
 * Single source of truth for reader-page image requests ([pageRequest]).
 *
 * U-26: prefetch ([ImagePrefetcher]) and display (`MangaPageImage` in
 * ReaderScreen) previously built requests independently and never agreed:
 * prefetch used `Size.ORIGINAL` + `RGB_565` while display decoded
 * view-sized ARGB_8888, so prefetched bitmaps sat in a memory-cache
 * keyspace the renderer never read. Both call sites now funnel through
 * [pageRequest], so a prefetch is a cache-hit for the page it precedes.
 *
 * U-27(a)/(b): every page request carries the `ReaderImageConfig` decode
 * cap (4096px max side) instead of an unbounded original-size decode —
 * an uncapped 1080x8000 webtoon strip at ARGB_8888 is ~34MB per bitmap,
 * the long-scroll OOM driver. Software bitmaps (`allowHardware(false)`)
 * are forced only when a pixel-reading transformation is attached
 * (SMART_CROP), matching what the transform actually needs; other pages
 * keep hardware bitmaps.
 */
object ReaderImageConfig {
    /** Long-side cap for reader-page decodes (Coil `Size` box; aspect preserved). */
    const val DECODE_MAX_SIDE_PX = 4096

    fun readerPageSize(): Size = Size(DECODE_MAX_SIDE_PX, DECODE_MAX_SIDE_PX)

    /** Software decode is required iff a transformation reads pixels. */
    fun needsSoftwareBitmap(imageFilter: ReaderImageFilter): Boolean =
        imageFilter == ReaderImageFilter.SMART_CROP
}

object ReaderImageRequest {
    /**
     * Builds the memory/disk-cache-warming page request shared by display
     * and prefetch. The [trace] label differs per caller (observability
     * only — it is not part of the cache key); every cache-key input
     * (data, size, precision, transformations, headers, policies) is
     * identical for both callers.
     */
    fun pageRequest(
        context: Context,
        page: ChapterPage,
        imageFilter: ReaderImageFilter,
        trace: String,
        crossfadeMs: Int = 0,
    ): ImageRequest {
        val withCrop = imageFilter == ReaderImageFilter.SMART_CROP
        return ImageRequest.Builder(context)
            .data(page.url)
            .size(ReaderImageConfig.readerPageSize())
            .precision(Precision.INEXACT)
            .apply { if (crossfadeMs > 0) crossfade(crossfadeMs) }
            .apply {
                if (ReaderImageConfig.needsSoftwareBitmap(imageFilter)) allowHardware(false)
            }
            .apply { if (withCrop) transformations(listOf(SmartCropTransformation())) }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .withFirebaseTrace(trace)
            .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
    }
}
