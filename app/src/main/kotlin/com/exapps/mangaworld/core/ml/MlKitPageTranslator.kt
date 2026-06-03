package com.exapps.mangaworld.core.ml

import android.graphics.BitmapFactory
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class PageTranslationLine(
    val originalText: String,
    val translatedText: String
)

data class PageTranslationResult(
    val lines: List<PageTranslationLine>,
    val sourceLanguageTag: String?
)

@Singleton
class MlKitPageTranslator @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    suspend fun translatePage(
        page: ChapterPage,
        targetLanguageTag: String = Locale.getDefault().language.ifBlank { "ar" }
    ): PageTranslationResult = withContext(Dispatchers.IO) {
        if (!settingsRepository.getAppSettings().first().mlKitEnabled) {
            return@withContext PageTranslationResult(emptyList(), null)
        }
        val bitmap = downloadBitmap(page) ?: return@withContext PageTranslationResult(emptyList(), null)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val languageIdentifier = LanguageIdentification.getClient()

        try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val blocks = text.textBlocks
                .mapNotNull { block ->
                    block.text.trim().takeIf { it.isNotBlank() }?.let { normalized ->
                        block.boundingBox to normalized
                    }
                }
                .sortedWith(
                    compareBy<Pair<android.graphics.Rect?, String>>(
                        { it.first?.top ?: Int.MAX_VALUE },
                        { it.first?.left ?: Int.MAX_VALUE }
                    )
                )
                .map { it.second }

            if (blocks.isEmpty()) {
                return@withContext PageTranslationResult(emptyList(), null)
            }

            val detectedLanguageTag = languageIdentifier.identifyLanguage(blocks.joinToString("\n")).await()
                .takeUnless { it == "und" }
            val targetLanguage = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: TranslateLanguage.ARABIC
            val sourceLanguage = detectedLanguageTag?.let(TranslateLanguage::fromLanguageTag)

            if (sourceLanguage == null || sourceLanguage == targetLanguage) {
                return@withContext PageTranslationResult(
                    lines = blocks.map { block -> PageTranslationLine(block, block) },
                    sourceLanguageTag = detectedLanguageTag
                )
            }

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )

            try {
                translator.downloadModelIfNeeded().await()
                val translatedLines = blocks.map { block ->
                    PageTranslationLine(
                        originalText = block,
                        translatedText = translator.translate(block).await()
                    )
                }
                PageTranslationResult(translatedLines, detectedLanguageTag)
            } finally {
                translator.close()
            }
        } finally {
            recognizer.close()
            languageIdentifier.close()
        }
    }

    private fun downloadBitmap(page: ChapterPage) = runCatching {
        val request = Request.Builder()
            .url(page.url)
            .apply { page.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
