package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationSyncManager @Inject constructor(
    private val annotationDao: ReaderAnnotationDao,
    private val sessionManager: FirebaseSessionManager
) {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun pushLocalAnnotations() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val localAnnotations = annotationDao.getAll()
        if (localAnnotations.isEmpty()) return

        val userRef = firestore.collection("users").document(uid)
            .collection("preferences").document("reader_annotations")

        val annotationsMap = localAnnotations.associate { annotation ->
            "${annotation.mangaId}_${annotation.chapterUrl}_${annotation.pageIndex}" to mapOf(
                "mangaId" to annotation.mangaId,
                "chapterUrl" to annotation.chapterUrl,
                "pageIndex" to annotation.pageIndex,
                "note" to annotation.note,
                "isBookmarked" to annotation.isBookmarked,
                "updatedAt" to annotation.updatedAt
            )
        }

        userRef.set(annotationsMap, SetOptions.merge()).await()
    }

    suspend fun pullRemoteAnnotations() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val userRef = firestore.collection("users").document(uid)
            .collection("preferences").document("reader_annotations")

        val snapshot = userRef.get().await()
        val remoteData = snapshot.data ?: return

        for ((_, value) in remoteData) {
            @Suppress("UNCHECKED_CAST")
            val annotationMap = value as? Map<String, Any> ?: continue
            val mangaId = annotationMap["mangaId"] as? String ?: continue
            val chapterUrl = annotationMap["chapterUrl"] as? String ?: continue
            val pageIndex = (annotationMap["pageIndex"] as? Number)?.toInt() ?: continue
            val note = annotationMap["note"] as? String
            val isBookmarked = annotationMap["isBookmarked"] as? Boolean ?: false
            val updatedAt = (annotationMap["updatedAt"] as? Number)?.toLong() ?: continue

            // Only update if remote is newer
            val localAnnotation = annotationDao.get(mangaId, chapterUrl, pageIndex)
            if (localAnnotation == null || updatedAt > localAnnotation.updatedAt) {
                annotationDao.upsert(
                    ReaderAnnotationEntity(
                        mangaId = mangaId,
                        chapterUrl = chapterUrl,
                        pageIndex = pageIndex,
                        note = note ?: "",
                        isBookmarked = isBookmarked,
                        updatedAt = updatedAt
                    )
                )
            }
        }
    }

    suspend fun syncAnnotations() {
        pushLocalAnnotations()
        pullRemoteAnnotations()
    }

    fun exportAnnotationsAsMarkdown(annotations: List<ReaderAnnotationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("# ملاحظات القراءة")
        sb.appendLine()

        annotations.groupBy { it.mangaId }.forEach { (mangaId, mangaAnnotations) ->
            sb.appendLine("## $mangaId")
            sb.appendLine()

            mangaAnnotations.sortedBy { it.pageIndex }.forEach { annotation ->
                sb.appendLine("### صفحة ${annotation.pageIndex + 1}")
                if (annotation.isBookmarked) {
                    sb.appendLine("⭐ مرجعية")
                }
                if (!annotation.note.isNullOrBlank()) {
                    sb.appendLine(annotation.note)
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }
}
