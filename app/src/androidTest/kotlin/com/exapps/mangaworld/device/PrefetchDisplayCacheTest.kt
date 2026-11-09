package com.exapps.mangaworld.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.memory.MemoryCache
import com.exapps.mangaworld.core.data.ReaderImageRequest
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.ReaderImageFilter
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64

/**
 * U-26 prefetch→display hit test (Test Lab matrix — see report for the
 * macrobenchmark spec). Prefetch and display build requests through the
 * shared [ReaderImageRequest] on the same single ImageLoader, so the
 * display load must be served from the memory cache with NO second
 * network fetch; before unification it always re-fetched (separate Hilt
 * memory cache + ORIGINAL/RGB_565 key mismatch).
 */
@RunWith(AndroidJUnit4::class)
class PrefetchDisplayCacheTest {

    @Test
    fun prefetchServesDisplayFromMemoryCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkCalls = AtomicInteger(0)
        val png = Base64.decode(PNG_1X1, Base64.DEFAULT)

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                networkCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(png.toResponseBody("image/png".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        val loader = ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(8 * 1024 * 1024)
                    .build()
            }
            .build()

        val page = ChapterPage(index = 0, url = "https://example.invalid/page1.png")
        runBlocking {
            // Prefetch (exactly the ImagePrefetcher path).
            loader.execute(
                ReaderImageRequest.pageRequest(
                    context = context, page = page,
                    imageFilter = ReaderImageFilter.NONE, trace = "prefetch_page",
                ),
            )
            assertEquals(1, networkCalls.get())

            // Display (exactly the MangaPageImage path).
            val displayed = loader.execute(
                ReaderImageRequest.pageRequest(
                    context = context, page = page,
                    imageFilter = ReaderImageFilter.NONE, trace = "reader_page",
                    crossfadeMs = 200,
                ),
            ).drawable != null
            assertTrue(displayed)
        }
        assertEquals("display re-fetched the page instead of hitting memory cache", 1, networkCalls.get())
    }

    private companion object {
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
