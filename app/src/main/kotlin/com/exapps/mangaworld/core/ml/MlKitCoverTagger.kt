package com.exapps.mangaworld.core.ml

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MlKitCoverTagger @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun generateTags(localCoverPath: String?, remoteCoverUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val bitmap = when {
            !localCoverPath.isNullOrBlank() && File(localCoverPath).exists() -> BitmapFactory.decodeFile(localCoverPath)
            !remoteCoverUrl.isNullOrBlank() -> downloadBitmap(remoteCoverUrl)
            else -> null
        } ?: return@withContext emptyList()

        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        try {
            labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
                .filter { it.confidence >= 0.65f }
                .map { label -> label.text.trim().lowercase(Locale.US) }
                .map { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
                .distinct()
                .take(3)
        } finally {
            labeler.close()
        }
    }

    private fun downloadBitmap(url: String) = runCatching {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
