package com.exapps.mangaworld.core.data

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.domain.model.ChapterPage
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import coil.request.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: Lazy<ImageLoader>
) {
    // U-17: unsynchronized MutableList in a singleton raced prefetch calls.
    private val pendingJobs = CopyOnWriteArrayList<Job>()
    // U-17: Coil enqueue returns a Disposable; retaining it makes cancelAll real.
    private val pendingDisposables = CopyOnWriteArrayList<Disposable>()

    fun cancelAll() {
        pendingJobs.forEach { it.cancel() }
        pendingJobs.clear()
        pendingDisposables.forEach { runCatching { it.dispose() } }
        pendingDisposables.clear()
        // NOTE: previously wiped the whole Coil memory cache here — "cancel
        // prefetch" destroyed display image cache. Removed (U-17).
    }

    suspend fun prefetchPages(pages: List<ChapterPage>, count: Int = pages.size.coerceAtMost(6)) {
        cancelAll()
        coroutineScope {
            pages.take(count).forEach { page ->
                val job = launch {
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
                    pendingDisposables.add(imageLoader.get().enqueue(request))
                }
                pendingJobs.add(job)
            }
        }
    }
}
