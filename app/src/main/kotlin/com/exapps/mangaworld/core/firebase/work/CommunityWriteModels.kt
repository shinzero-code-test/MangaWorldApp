package com.exapps.mangaworld.core.firebase.work

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * U-18e: the kinds of community write that survive process death.
 *
 * A comment write and a review write are both IDEMPOTENT when replayed:
 * - a comment is stored under the write id, so a replay overwrites the same
 *   document instead of creating a second one;
 * - a review is stored under the author's uid (one review per user per
 *   manga), so a replay updates the same document.
 *
 * That is exactly why these two kinds can be queued and retried, while a
 * REPLY cannot: the dashboard assigns the reply id server-side, so a replay
 * would create a second reply (see the U-19 endpoint).
 */
enum class CommunityWriteKind { COMMENT, REVIEW }

/**
 * One durable community write: the intent to publish, persisted BEFORE the
 * first attempt so a process death cannot swallow it.
 *
 * The author snapshot is captured at enqueue time on purpose: the worker may
 * run minutes later, and a comment must keep the identity it was written
 * with (the Firestore rules bind `authorUid` to the session that writes it).
 */
data class PendingCommunityWrite(
    /** Write id. For a comment this IS the comment document id (idempotent replay). */
    val id: String,
    val kind: CommunityWriteKind,
    val mangaId: String,
    val chapterUrl: String?,
    val slug: String,
    val sourceId: String,
    val text: String,
    val spoiler: Boolean,
    val authorUid: String,
    val authorName: String,
    val authorUsername: String,
    val authorAvatarUrl: String?,
    val authorBadge: String?,
    val createdAt: Long,
    /** Review-only fields. */
    val rating: Int? = null,
    val reviewTitle: String? = null,
    val reviewBody: String? = null,
    /** Retry bookkeeping (persisted so a process restart keeps the budget). */
    val attempts: Int = 0,
    /** Last failure, for diagnostics. Never user-facing text. */
    val lastError: String? = null
)

/** A write that can never succeed as-is and is therefore never retried. */
data class CommunityWriteFailure(
    val id: String,
    val kind: CommunityWriteKind,
    val error: String,
    val at: Long
)

/**
 * Shared chapter key: 24 hex chars (96 bits) of the SHA-256 of the chapter
 * URL. It mirrors the dashboard's `stableChapterKey` — do NOT change one side
 * without the other.
 */
internal fun communityChapterKey(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(24)
}

/** @mentions in a community text, capped at the rules' limit (10). */
internal fun communityMentions(text: String): List<String> =
    Regex("@([A-Za-z0-9_]{3,30})")
        .findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .take(MAX_COMMUNITY_MENTIONS)
        .toList()

internal const val MAX_COMMUNITY_MENTIONS = 10

// ─── Persistence codec (pure, unit-testable) ───────────────────────────────

internal fun PendingCommunityWrite.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("kind", kind.name)
    put("mangaId", mangaId)
    put("chapterUrl", chapterUrl ?: JSONObject.NULL)
    put("slug", slug)
    put("sourceId", sourceId)
    put("text", text)
    put("spoiler", spoiler)
    put("authorUid", authorUid)
    put("authorName", authorName)
    put("authorUsername", authorUsername)
    put("authorAvatarUrl", authorAvatarUrl ?: JSONObject.NULL)
    put("authorBadge", authorBadge ?: JSONObject.NULL)
    put("createdAt", createdAt)
    put("rating", rating ?: JSONObject.NULL)
    put("reviewTitle", reviewTitle ?: JSONObject.NULL)
    put("reviewBody", reviewBody ?: JSONObject.NULL)
    put("attempts", attempts)
    put("lastError", lastError ?: JSONObject.NULL)
}

internal fun pendingCommunityWriteFromJson(json: JSONObject): PendingCommunityWrite? {
    val id = json.optString("id", "")
    if (id.isBlank()) return null
    val kind = runCatching { CommunityWriteKind.valueOf(json.optString("kind", "")) }.getOrNull() ?: return null
    return PendingCommunityWrite(
        id = id,
        kind = kind,
        mangaId = json.optString("mangaId", ""),
        chapterUrl = json.optNullableString("chapterUrl"),
        slug = json.optString("slug", ""),
        sourceId = json.optString("sourceId", ""),
        text = json.optString("text", ""),
        spoiler = json.optBoolean("spoiler", false),
        authorUid = json.optString("authorUid", ""),
        authorName = json.optString("authorName", ""),
        authorUsername = json.optString("authorUsername", ""),
        authorAvatarUrl = json.optNullableString("authorAvatarUrl"),
        authorBadge = json.optNullableString("authorBadge"),
        createdAt = json.optLong("createdAt", 0L),
        rating = if (json.isNull("rating")) null else json.optInt("rating", 0),
        reviewTitle = json.optNullableString("reviewTitle"),
        reviewBody = json.optNullableString("reviewBody"),
        attempts = json.optInt("attempts", 0),
        lastError = json.optNullableString("lastError")
    )
}

/** `null` for a missing/JSON-null field (org.json stores nulls as NULL). */
internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").ifBlank { null }

internal fun encodeCommunityWrites(writes: List<PendingCommunityWrite>): String {
    val array = JSONArray()
    writes.forEach { array.put(it.toJson()) }
    return array.toString()
}

/**
 * Decodes a persisted outbox. A malformed entry is SKIPPED (never fatal):
 * one corrupt row must not strand every other pending write.
 */
internal fun decodeCommunityWrites(raw: String): List<PendingCommunityWrite> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            runCatching { pendingCommunityWriteFromJson(array.optJSONObject(index) ?: JSONObject()) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
