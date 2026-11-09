package com.exapps.mangaworld.core.data

import android.content.Context
import coil.ImageLoader
import coil.request.Disposable
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.ReaderImageFilter
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /**
     * U-26: warms the SAME loader + cache keyspace the reader renders
     * from. Screens use `ctx.imageLoader` (Coil singleton factory) and
     * this prefetcher's injected [ImageLoader] used to be a separate Hilt
     * instance — the Hilt binding now delegates to `SingletonImageLoader`,
     * so `imageLoader.get()` here IS the display loader. Requests are
     * built by the shared [ReaderImageRequest] (decode-capped size,
     * INEXACT, display-matching transformation/software gating), so a
     * prefetched page is a memory-cache hit when the reader reaches it.
     */
    suspend fun prefetchPages(
        pages: List<ChapterPage>,
        count: Int = pages.size.coerceAtMost(6),
        imageFilter: ReaderImageFilter = ReaderImageFilter.NONE,
    ) {
        cancelAll()
        coroutineScope {
            pages.take(count).forEach { page ->
                val job = launch {
                    val request = ReaderImageRequest.pageRequest(
                        context = context,
                        page = page,
                        imageFilter = imageFilter,
                        trace = "prefetch_page",
                    )
                    pendingDisposables.add(imageLoader.get().enqueue(request))
                }
                pendingJobs.add(job)
            }
        }
    }
}
