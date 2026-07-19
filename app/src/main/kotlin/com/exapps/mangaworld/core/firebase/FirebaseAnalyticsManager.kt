package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAnalyticsManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val analytics = FirebaseAnalytics.getInstance(context)

    fun setUserId(userId: String?) {
        analytics.setUserId(userId)
    }

    fun setUserProperty(name: String, value: String?) {
        analytics.setUserProperty(name.toFirebaseKey(maxLength = 24), value?.trim()?.take(36))
    }

    fun logScreen(screenName: String) {
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName.take(36))
        })
    }

    fun logEvent(name: String, params: Map<String, Any?> = emptyMap()) {
        analytics.logEvent(name.toFirebaseEventName(), Bundle().apply {
            params.forEach { (key, value) -> putValue(key.toFirebaseKey(), value) }
        })
    }

    fun logMangaViewed(
        mangaId: String,
        sourceId: String,
        genres: List<String> = emptyList(),
        chapterCount: Int? = null
    ) {
        logEvent(
            name = "manga_viewed",
            params = buildMap {
                put("manga_id", mangaId.takeLast(36))
                put("source_id", sourceId)
                put("genre_primary", genres.firstOrNull())
                put("chapter_count", chapterCount)
            }
        )
    }

    fun logChapterRead(
        mangaId: String,
        sourceId: String,
        chapterNumber: Float?,
        totalPages: Int,
        readerMode: String
    ) {
        logEvent(
            name = "chapter_read",
            params = mapOf(
                "manga_id" to mangaId.takeLast(36),
                "source_id" to sourceId,
                "chapter_number" to chapterNumber?.toDouble(),
                "total_pages" to totalPages,
                "reader_mode" to readerMode.lowercase(Locale.US)
            )
        )
    }

    fun logSearchQuery(query: String, sourceId: String?, enabledSources: Int) {
        logEvent(
            name = "search_query",
            params = mapOf(
                "query" to query.trim().take(100),
                "query_length" to query.trim().length,
                "source_id" to (sourceId ?: "all"),
                "enabled_sources" to enabledSources
            )
        )
    }

    fun logDownloadStatus(
        mangaId: String,
        sourceId: String,
        status: String,
        totalPages: Int,
        retryCount: Int = 0,
        reason: String? = null
    ) {
        logEvent(
            name = "download_status",
            params = mapOf(
                "manga_id" to mangaId.takeLast(36),
                "source_id" to sourceId,
                "status" to status.lowercase(Locale.US),
                "total_pages" to totalPages,
                "retry_count" to retryCount,
                "reason" to reason?.take(40)
            )
        )
    }

    fun logHomeLayoutExposure(layoutVariant: String, sourceId: String) {
        logEvent(
            name = "home_layout_exposure",
            params = mapOf(
                "variant" to layoutVariant.ifBlank { "default" },
                "source_id" to sourceId
            )
        )
    }

    fun logNotificationReceived(type: String, hasImage: Boolean) {
        logEvent(
            name = "notification_received",
            params = mapOf(
                "type" to type,
                "has_image" to hasImage
            )
        )
    }

    fun logMlTranslation(sourceLanguage: String?, targetLanguage: String, blockCount: Int) {
        logEvent(
            name = "ml_translation",
            params = mapOf(
                "source_language" to sourceLanguage,
                "target_language" to targetLanguage,
                "block_count" to blockCount
            )
        )
    }

    fun logSmartReplySurface(surface: String, suggestionCount: Int) {
        logEvent(
            name = "smart_reply_surface",
            params = mapOf(
                "surface" to surface,
                "suggestion_count" to suggestionCount
            )
        )
    }

    fun logSmartReplySelected(surface: String, replyLength: Int) {
        logEvent(
            name = "smart_reply_selected",
            params = mapOf(
                "surface" to surface,
                "reply_length" to replyLength
            )
        )
    }

    fun logCoverTagsGenerated(sourceId: String, tagCount: Int) {
        logEvent(
            name = "cover_tags_generated",
            params = mapOf(
                "source_id" to sourceId,
                "tag_count" to tagCount
            )
        )
    }

    private fun Bundle.putValue(key: String, value: Any?) {
        when (value) {
            null -> Unit
            is String -> putString(key, value.take(100))
            is Int -> putLong(key, value.toLong())
            is Long -> putLong(key, value)
            is Float -> putDouble(key, value.toDouble())
            is Double -> putDouble(key, value)
            is Boolean -> putLong(key, if (value) 1L else 0L)
            else -> putString(key, value.toString().take(100))
        }
    }
}

private val RESERVED_PREFIXES = setOf("firebase_", "google_", "ga_")

private fun String.toFirebaseEventName(): String =
    toFirebaseKey(maxLength = 40).let { sanitized ->
        if (RESERVED_PREFIXES.any { sanitized.startsWith(it) }) "mw_$sanitized".take(40) else sanitized
    }

private fun String.toFirebaseKey(maxLength: Int = 40): String {
    val normalized = lowercase(Locale.US)
        .replace("[^a-z0-9_]".toRegex(), "_")
        .replace("_+".toRegex(), "_")
        .trim('_')
        .ifBlank { "value" }
    val prefixed = if (normalized.first().isDigit()) "mw_$normalized" else normalized
    return prefixed.take(maxLength)
}
