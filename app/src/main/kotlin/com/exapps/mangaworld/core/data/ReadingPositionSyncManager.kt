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

    suspend fun pushLocalPositions() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val localProgress = progressDao.getAll()
        if (localProgress.isEmpty()) return

        val userRef = firestore.collection("users").document(uid)
            .collection("preferences").document("reading_positions")

        val positionsMap = localProgress.associate { progress ->
            "${progress.mangaId}_${progress.chapterNumber}" to mapOf(
                "mangaId" to progress.mangaId,
                "chapterNumber" to progress.chapterNumber,
                "currentPage" to progress.currentPage,
                "totalPages" to progress.totalPages,
                "updatedAt" to progress.updatedAt
            )
        }

        userRef.set(positionsMap, SetOptions.merge()).await()
    }

    suspend fun pullRemotePositions() {
        val uid = sessionManager.ensureGuestSession() ?: return
        val userRef = firestore.collection("users").document(uid)
            .collection("preferences").document("reading_positions")

        val snapshot = userRef.get().await()
        val remoteData = snapshot.data ?: return

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

    suspend fun syncPositions() {
        pushLocalPositions()
        pullRemotePositions()
    }
}
