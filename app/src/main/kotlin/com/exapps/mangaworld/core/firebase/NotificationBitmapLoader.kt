package com.exapps.mangaworld.core.firebase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory-bounded cover/image download for notifications.
 *
 * Extracted from MangaWorldFirebaseMessagingService so every notification
 * producer (FCM pushes, suggestion worker) shares one hardened path: hard
 * byte cap, bounds pass + downsampling to notification size, RGB_565. A
 * hostile or huge image previously caused OOM-level decodes on low-end
 * devices. Null means "render the text style instead" — callers must always
 * have a bitmap-free fallback.
 */
@Singleton
class NotificationBitmapLoader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun load(imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank() || imageUrl.startsWith("data:")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val declared = resp.body?.contentLength() ?: -1L
                    if (declared > MAX_IMAGE_BYTES) return@use null

                    val bytes: ByteArray? = resp.body!!.byteStream().use { input ->
                        val buffer = java.io.ByteArrayOutputStream(minOf(declared.takeIf { it > 0 } ?: 64_000L, MAX_IMAGE_BYTES).toInt())
                        val chunk = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read == -1) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) return@use null
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    }
                    if (bytes == null) return@use null

                    // Bounds pass → sample size → memory-bounded decode.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= TARGET_DIMENSION ||
                        bounds.outHeight / (sample * 2) >= TARGET_DIMENSION) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to load notification image: ${e.message}")
            }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "NotificationBitmaps"
        /** 2 MB hard cap on notification-image downloads. */
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        /** Big-picture decode target (longest edge) — notifications never render larger. */
        const val TARGET_DIMENSION = 1024
    }
}
