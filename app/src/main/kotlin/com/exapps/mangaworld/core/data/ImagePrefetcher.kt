package com.exapps.mangaworld.core.data

import android.content.Context
import android.graphics.Bitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.ChapterPage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun prefetchPages(pages: List<ChapterPage>, count: Int = pages.size.coerceAtMost(6)) {
        pages.take(count).forEach { page ->
            val request = ImageRequest.Builder(context)
                .data(page.url)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .precision(Precision.INEXACT)
                .size(1600, 4096)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .withFirebaseTrace("prefetch_page")
                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            context.imageLoader.enqueue(request)
        }
    }
}
