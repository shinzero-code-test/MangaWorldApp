package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.google.firebase.firestore.DocumentSnapshot

internal object FirebaseSyncMerge {
    // Local entries win ties so an equal clock value cannot roll a device back.
    // (Holds because maxBy returns the FIRST maximal element and local is
    // concatenated first — the groups below preserve that order.)
    fun favorites(
        local: Collection<FavoriteEntity>,
        remote: Collection<FavoriteEntity>
    ): List<FavoriteEntity> =
        (local + remote)
            .groupBy { it.mangaId }
            .values
            .map { candidates -> candidates.maxBy { it.addedAt } }

    fun history(
        local: Collection<ReadingHistoryEntity>,
        remote: Collection<ReadingHistoryEntity>
    ): List<ReadingHistoryEntity> =
        (local + remote)
            .groupBy { it.mangaId }
            .values
            .map { candidates -> candidates.maxBy { it.lastReadAt } }

    fun annotations(
        local: Collection<ReaderAnnotationEntity>,
        remote: Collection<ReaderAnnotationEntity>
    ): List<ReaderAnnotationEntity> =
        (local + remote)
            .groupBy(::annotationDocId)
            .values
            .map { candidates -> candidates.maxBy { it.updatedAt } }

    fun annotationDocId(entity: ReaderAnnotationEntity): String {
        // 64-bit SHA-256 prefix: 32-bit hashCode() collisions across chapter
        // URLs of one manga silently merged distinct annotations (F11).
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(entity.chapterUrl.toByteArray())
        val short = digest.take(8).joinToString("") { "%02x".format(it) }
        return listOf(entity.mangaId, short, entity.pageIndex.toString())
            .joinToString("_")
    }

    /** Pre-v8.2.7 key — matches old docs/tombstones during migration. */
    fun legacyAnnotationDocId(entity: ReaderAnnotationEntity): String =
        listOf(entity.mangaId, entity.chapterUrl.hashCode().toString(), entity.pageIndex.toString())
            .joinToString("_")

    // ─── Pull deserialization ─────────────────────────────────────────────
    //
    // Firestore's toObject() requires a public no-arg constructor, which none
    // of these Room entities have (only SyncTombstone is all-defaults). Using
    // toObject here throws on the first remote doc and aborts the whole pull,
    // so history/favorites silently never download (item 6). These tolerant
    // field readers return null for corrupt docs instead.

    /** mangaId falls back to the doc id (push writes use mangaId as doc id). */
    fun favorite(doc: DocumentSnapshot): FavoriteEntity? = runCatching {
        val mangaId = doc.getString("mangaId")?.takeIf { it.isNotBlank() } ?: doc.id
        if (mangaId.isBlank()) return null
        FavoriteEntity(
            mangaId = mangaId,
            slug = doc.getString("slug").orEmpty(),
            title = doc.getString("title").orEmpty(),
            coverUrl = doc.getString("coverUrl").orEmpty(),
            sourceId = doc.getString("sourceId").orEmpty(),
            addedAt = doc.getLong("addedAt") ?: 0L,
            readChapters = doc.getLong("readChapters")?.toInt() ?: 0,
            totalChapters = doc.getLong("totalChapters")?.toInt() ?: 0,
            readingStatus = doc.getString("readingStatus"),
            isFavorite = doc.getBoolean("isFavorite") ?: true
        )
    }.getOrNull()

    fun history(doc: DocumentSnapshot): ReadingHistoryEntity? = runCatching {
        val mangaId = doc.getString("mangaId")?.takeIf { it.isNotBlank() } ?: doc.id
        if (mangaId.isBlank()) return null
        ReadingHistoryEntity(
            mangaId = mangaId,
            slug = doc.getString("slug").orEmpty(),
            title = doc.getString("title").orEmpty(),
            coverUrl = doc.getString("coverUrl").orEmpty(),
            sourceId = doc.getString("sourceId").orEmpty(),
            lastChapterNumber = doc.getDouble("lastChapterNumber")?.toFloat() ?: 0f,
            lastChapterUrl = doc.getString("lastChapterUrl").orEmpty(),
            lastReadAt = doc.getLong("lastReadAt") ?: 0L,
            readChapters = doc.getLong("readChapters")?.toInt() ?: 0,
            totalChapters = doc.getLong("totalChapters")?.toInt() ?: 0,
            durationMs = doc.getLong("durationMs") ?: 0L
        )
    }.getOrNull()

    fun annotation(doc: DocumentSnapshot): ReaderAnnotationEntity? = runCatching {
        val mangaId = doc.getString("mangaId")?.takeIf { it.isNotBlank() } ?: return null
        val chapterUrl = doc.getString("chapterUrl")?.takeIf { it.isNotBlank() } ?: return null
        ReaderAnnotationEntity(
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            pageIndex = doc.getLong("pageIndex")?.toInt() ?: 0,
            note = doc.getString("note").orEmpty(),
            isBookmarked = doc.getBoolean("isBookmarked") == true,
            updatedAt = doc.getLong("updatedAt") ?: 0L
        )
    }.getOrNull()
}
