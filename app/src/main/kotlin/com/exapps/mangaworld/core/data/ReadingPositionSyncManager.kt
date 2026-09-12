package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.data.local.AppPreferences
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
    private val sessionManager: FirebaseSessionManager,
    private val prefs: AppPreferences
) {
    private val firestore = FirebaseFirestore.getInstance()

    /** Max positions per Firestore document to stay safely under the 1 MB limit. */
    private val CHUNK_SIZE = 200

    /** Maximum number of chunk documents to scan (200 * 50 = 10k positions max). */
    private val MAX_CHUNKS = 50

    /** Pull stops after this many consecutive missing chunks (FS-5: was always 50 reads). */
    private val MAX_CONSECUTIVE_MISSES = 5

    /** FS-13: anonymous sessions never touch cloud sync (no session creation either). */
    private fun namedUid(): String? =
        sessionManager.currentUser()?.takeIf { !it.isAnonymous }?.uid

    suspend fun pushLocalPositions() {
        val uid = namedUid() ?: return
        val localProgress = progressDao.getAll()
        if (localProgress.isEmpty()) return
        // FS-5: watermark gate — skip the whole push when nothing changed
        // since the last successful one (writes stay full-rewrite so the
        // stable chunk layout + stale cleanup below remain correct).
        // First run (watermark 0) always pushes, preserving old behavior.
        val watermark = prefs.getLastPositionsPush()
        val dirty = localProgress.filter { it.updatedAt > watermark }
        if (dirty.isEmpty()) return

        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // Stable chunk layout (sorted): chunk indexes must not shift between
        // pushes or stale-chunk cleanup deletes live data.
        val sorted = localProgress.sortedWith(compareBy({ it.mangaId }, { it.chapterNumber }))
        val chunksWritten = (sorted.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        sorted.chunked(CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
            val positionsMap = chunk.associate { progress ->
                "${progress.mangaId}_${progress.chapterNumber}" to mapOf(
                    "mangaId" to progress.mangaId,
                    "chapterNumber" to progress.chapterNumber,
                    "currentPage" to progress.currentPage,
                    "totalPages" to progress.totalPages,
                    "updatedAt" to progress.updatedAt
                )
            }
            userRef.document("reading_positions_$chunkIndex").set(positionsMap, SetOptions.merge()).await()
        }
        // Shrunk libraries leave stale high-index chunks behind — remove them
        // so pulls stay dense and orphaned positions can't resurrect.
        runCatching {
            val stale = userRef.get().await().documents
                .mapNotNull { it.id.removePrefix(CHUNK_PREFIX).toIntOrNull() }
                .filter { it >= chunksWritten }
            stale.forEach { runCatching { userRef.document("$CHUNK_PREFIX$it").delete().await() } }
        }
        // Stamp only on success: a failed push keeps the old watermark so the
        // next attempt retries the same rows instead of skipping them forever.
        prefs.setLastPositionsPush(System.currentTimeMillis())
    }

    suspend fun pullRemotePositions() {
        val uid = namedUid() ?: return
        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // Scan chunk documents — continue through gaps and up to MAX_CHUNKS
        // instead of breaking on the first missing document, but stop after a
        // run of consecutive misses (chunks are dense post-cleanup).
        var misses = 0
        for (chunkIndex in 0 until MAX_CHUNKS) {
            val snapshot = userRef.document("reading_positions_$chunkIndex").get().await()
            val remoteData = snapshot.data
            if (remoteData == null) {
                if (++misses >= MAX_CONSECUTIVE_MISSES) break
                continue
            }
            misses = 0

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

    private companion object {
        const val CHUNK_PREFIX = "reading_positions_"
    }
}
