package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.data.local.entity.ReadingProgressEntity
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingPositionSyncManager @Inject constructor(
    private val progressDao: ReadingProgressDao,
    private val sessionManager: FirebaseSessionManager
) {
    private val firestore = FirebaseFirestore.getInstance()

    /** Max positions per Firestore document to stay safely under the 1 MB limit. */
    private val CHUNK_SIZE = 200

    /** Maximum number of chunk documents to scan (200 * 50 = 10k positions max). */
    private val MAX_CHUNKS = 50

    suspend fun pushLocalPositions() {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val localProgress = progressDao.getAll()
        if (localProgress.isEmpty()) return

        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // Write in chunks of [CHUNK_SIZE] to stay under Firestore's 1MB document limit
        localProgress.chunked(CHUNK_SIZE).forEach { chunk ->
            val positionsMap = chunk.associate { progress ->
                "${progress.mangaId}_${progress.chapterNumber}" to mapOf(
                    "mangaId" to progress.mangaId,
                    "chapterNumber" to progress.chapterNumber,
                    "currentPage" to progress.currentPage,
                    "totalPages" to progress.totalPages,
                    "updatedAt" to progress.updatedAt
                )
            }
            val chunkIndex = localProgress.indexOf(chunk.first()) / CHUNK_SIZE
            userRef.document("reading_positions_$chunkIndex").set(positionsMap, SetOptions.merge()).await()
        }
    }

    suspend fun pullRemotePositions() {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // Scan chunk documents — continue through gaps and up to MAX_CHUNKS
        // instead of breaking on the first missing document
        for (chunkIndex in 0 until MAX_CHUNKS) {
            val snapshot = userRef.document("reading_positions_$chunkIndex").get().await()
            val remoteData = snapshot.data ?: continue  // skip gaps, don't break

            for ((_, value) in remoteData) {
                @Suppress("UNCHECKED_CAST")
                val positionMap = value as? Map<String, Any> ?: continue
                val mangaId = positionMap["mangaId"] as? String ?: continue
                val chapterNumber = (positionMap["chapterNumber"] as? Number)?.toFloat() ?: continue
                val currentPage = (positionMap["currentPage"] as? Number)?.toInt() ?: continue
                val totalPages = (positionMap["totalPages"] as? Number)?.toInt() ?: continue
                val updatedAt = (positionMap["updatedAt"] as? Number)?.toLong() ?: continue

                // Only update if remote is newer
                val localProgress = progressDao.get(mangaId, chapterNumber)
                if (localProgress == null || updatedAt > localProgress.updatedAt) {
                    progressDao.save(
                        ReadingProgressEntity(
                            mangaId = mangaId,
                            chapterNumber = chapterNumber,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        }
    }

    suspend fun syncPositions() {
        pushLocalPositions()
        pullRemotePositions()
    }
}
