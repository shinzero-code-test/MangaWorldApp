package com.exapps.mangaworld.core.data

import android.content.Context
import android.graphics.Bitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.ChapterPage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pendingRequests = mutableListOf<ImageRequest>()

    fun cancelAll() {
        pendingRequests.forEach { context.imageLoader.cancel(it) }
        pendingRequests.clear()
    }

    fun prefetchPages(pages: List<ChapterPage>, count: Int = pages.size.coerceAtMost(6)) {
        cancelAll()
        pages.take(count).forEach { page ->
            val request = ImageRequest.Builder(context)
                .data(page.url)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .precision(Precision.INEXACT)
                .size(Size.ORIGINAL)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .withFirebaseTrace("prefetch_page")
                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            context.imageLoader.enqueue(request)
            pendingRequests.add(request)
        }
    }
}
