package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity

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
}
