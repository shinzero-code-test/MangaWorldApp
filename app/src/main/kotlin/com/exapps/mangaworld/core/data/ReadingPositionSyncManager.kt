package com.exapps.mangaworld.core.data

import android.util.Log
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.data.local.entity.ReadingProgressEntity
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.core.util.suspendRunCatching
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // U-12: serialize push/pull — the old read-then-write pair raced (a pull
    // landing between the get-then-push and save dropped the newest write).
    private val mutex = Mutex()

    /** Max positions per Firestore document to stay safely under the 1 MB limit. */
    private val CHUNK_SIZE = 200

    /** Pull document cap (safety bound only — pagination walks until empty). */
    private val MAX_PULL_DOCS = 500

    /** FS-13: anonymous sessions never touch cloud sync (no session creation either). */
    private fun namedUid(): String? =
        sessionManager.currentUser()?.takeIf { !it.isAnonymous }?.uid

    suspend fun pushLocalPositions() = mutex.withLock {
        val uid = namedUid() ?: return@withLock
        val localProgress = progressDao.getAll()
        if (localProgress.isEmpty()) return@withLock
        // FS-5: watermark gate — skip the whole push when nothing changed
        // since the last successful one (writes stay full-rewrite so the
        // stable chunk layout + stale cleanup below remain correct).
        // First run (watermark 0) always pushes, preserving old behavior.
        val watermark = prefs.getLastPositionsPush()
        val dirty = localProgress.filter { it.updatedAt > watermark }
        if (dirty.isEmpty()) return@withLock

        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // Stable chunk layout (sorted): chunk indexes must not shift between
        // pushes or stale-chunk cleanup deletes live data.
        val sorted = localProgress.sortedWith(compareBy({ it.mangaId }, { it.chapterNumber }))
        val chunksWritten = (sorted.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        sorted.chunked(CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
            // U-12 push guard (mirror of the pull guard): read the remote
            // chunk's per-position timestamps and only set strictly newer
            // entries. An untouched SetOptions.merge() rewrite used to let a
            // stale device regress a newer position (push order won).
            val remoteData = userRef.document("reading_positions_$chunkIndex").get().await().data
            val remoteTs = remoteData?.mapValues { (_, value) ->
                @Suppress("UNCHECKED_CAST")
                (value as? Map<String, Any>)?.get("updatedAt") as? Number
            } ?: emptyMap()
            val newer = chunk.filter { progress ->
                val key = "${progress.mangaId}_${progress.chapterNumber}"
                shouldPushPosition(progress.updatedAt, remoteTs[key]?.toLong())
            }
            if (newer.isEmpty()) return@forEachIndexed
            val positionsMap = newer.associate { progress ->
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
        // U-13: cancellation-aware — a cancelled cleanup aborts the push
        // BEFORE the watermark below is stamped, so the next attempt retries
        // instead of silently skipping the stale-chunk sweep.
        suspendRunCatching {
            val stale = userRef.get().await().documents
                .mapNotNull { it.id.removePrefix(CHUNK_PREFIX).toIntOrNull() }
                .filter { it >= chunksWritten }
            stale.forEach { suspendRunCatching { userRef.document("$CHUNK_PREFIX$it").delete().await() } }
        }
        // Stamp only on success: a failed push keeps the old watermark so the
        // next attempt retries the same rows instead of skipping them forever.
        prefs.setLastPositionsPush(System.currentTimeMillis())
    }

    /**
     * Pull every reading_positions_* doc via document-id pagination.
     * Returns true when a chunk tail was truncated (safety bound hit) — the
     * merge path surfaces `truncated` the same way (CloudRestorePreview).
     */
    suspend fun pullRemotePositions(): Boolean = mutex.withLock {
        val uid = namedUid() ?: return@withLock false
        val userRef = firestore.collection("users").document(uid)
            .collection("preferences")

        // U-12: paginate with a start-after cursor until the collection is
        // exhausted — the old fixed 50-chunk scan (10k) silently dropped the
        // tail and never told anyone.
        var lastDocId: String? = null
        var pages = 0
        var truncated = false
        while (true) {
            val query = if (lastDocId == null) {
                userRef.orderBy(FieldPath.documentId()).limit(50)
            } else {
                userRef.orderBy(FieldPath.documentId()).startAfter(lastDocId).limit(50)
            }
            val snap = query.get().await()
            if (snap.isEmpty) break
            snap.documents.forEach { doc ->
                if (!doc.id.startsWith(CHUNK_PREFIX)) return@forEach
                val remoteData = doc.data ?: return@forEach
                for ((_, value) in remoteData) {
                    @Suppress("UNCHECKED_CAST")
                    val positionMap = value as? Map<String, Any> ?: continue
                    val mangaId = positionMap["mangaId"] as? String ?: continue
                    val chapterNumber = (positionMap["chapterNumber"] as? Number)?.toFloat() ?: continue
                    val currentPage = (positionMap["currentPage"] as? Number)?.toInt() ?: continue
                    val totalPages = (positionMap["totalPages"] as? Number)?.toInt() ?: continue
                    val updatedAt = (positionMap["updatedAt"] as? Number)?.toLong() ?: continue

                    // Only update if remote is newer (ties keep local).
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
            pages++
            if (pages >= MAX_PULL_DOCS) {
                truncated = true
                break
            }
            if (snap.size() < 50) break
            lastDocId = snap.documents.last().id
        }
        if (truncated) {
            Log.w("ReadingPositionSync", "Position pull truncated at $MAX_PULL_DOCS pages")
        }
        truncated
    }

    suspend fun syncPositions() {
        pushLocalPositions()
        pullRemotePositions()
    }

    companion object {
        /** U-12 push guard: only strictly newer locals push (ties keep cloud). Internal for unit tests. */
        internal fun shouldPushPosition(localUpdatedAt: Long, remoteUpdatedAt: Long?): Boolean =
            remoteUpdatedAt == null || localUpdatedAt > remoteUpdatedAt

        private const val CHUNK_PREFIX = "reading_positions_"
    }
}
