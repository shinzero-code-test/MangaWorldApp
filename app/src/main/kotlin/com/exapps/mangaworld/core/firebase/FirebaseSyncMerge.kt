package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity

internal object FirebaseSyncMerge {
    // Local entries win ties so an equal clock value cannot roll a device back.
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

    fun annotationDocId(entity: ReaderAnnotationEntity): String =
        listOf(entity.mangaId, entity.chapterUrl.hashCode().toString(), entity.pageIndex.toString())
            .joinToString("_")
}
