package com.exapps.mangaworld.core.firebase.work

import com.exapps.mangaworld.core.firebase.toMap
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.MangaReview
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-18e: performs one durable community write.
 *
 * The interface is the seam that lets the worker and the foreground path run
 * the SAME code (a worker-only copy of the write logic would drift from the
 * production one within a release) and lets the JVM suite drive the retry
 * policy with a fake executor.
 */
interface CommunityWriteExecutor {
    /**
     * Publishes [write] and returns the id of the created/updated document.
     *
     * @throws Exception when the write did not land. The caller classifies the
     *   failure (retryable vs terminal) — see [classifyCommunityWriteFailure].
     */
    suspend fun execute(write: PendingCommunityWrite): String
}

/**
 * Production [CommunityWriteExecutor]: Firestore for comments and reviews.
 *
 * Both writes are idempotent by construction, so replaying them after a
 * process death cannot duplicate anything:
 * - a comment is stored under the write id;
 * - a review is stored under the author's uid (one review per user per manga).
 */
@Singleton
class FirestoreCommunityWriteExecutor @Inject constructor(
    private val firestore: FirebaseFirestore
) : CommunityWriteExecutor {

    override suspend fun execute(write: PendingCommunityWrite): String = when (write.kind) {
        CommunityWriteKind.COMMENT -> writeComment(write)
        CommunityWriteKind.REVIEW -> writeReview(write)
    }

    private suspend fun writeComment(write: PendingCommunityWrite): String {
        val comment = CommunityComment(
            id = write.id,
            mangaId = write.mangaId,
            chapterUrl = write.chapterUrl,
            slug = write.slug,
            sourceId = write.sourceId,
            authorUid = write.authorUid,
            authorName = write.authorName,
            authorUsername = write.authorUsername,
            authorAvatarUrl = write.authorAvatarUrl,
            authorBadge = write.authorBadge ?: DEFAULT_BADGE,
            text = write.text,
            mentions = communityMentions(write.text),
            spoiler = write.spoiler,
            createdAt = write.createdAt
        )
        commentsCollection(write.mangaId, write.chapterUrl)
            .document(write.id)
            .set(comment.toMap())
            .await()
        return write.id
    }

    private suspend fun writeReview(write: PendingCommunityWrite): String {
        val rating = (write.rating ?: 0).coerceIn(1, 5)
        val reviewRef = firestore.collection("community_manga")
            .document(write.mangaId)
            .collection("reviews")
            .document(write.authorUid)
        val existing = reviewRef.get().await()
        val now = System.currentTimeMillis()
        if (existing.exists()) {
            // Edit path: keep `createdAt` and every field the rules do not
            // allow us to rewrite.
            reviewRef.update(
                mapOf(
                    "rating" to rating,
                    "title" to write.reviewTitle.orEmpty(),
                    "body" to write.reviewBody.orEmpty(),
                    // Refresh the denormalized author snapshot so the card always
                    // matches the current public profile (allowed by rules).
                    "authorName" to write.authorName,
                    "authorUsername" to write.authorUsername,
                    "authorAvatarUrl" to write.authorAvatarUrl,
                    "isDeleted" to false,
                    "updatedAt" to now
                )
            ).await()
        } else {
            val review = MangaReview(
                id = write.authorUid,
                mangaId = write.mangaId,
                authorUid = write.authorUid,
                authorName = write.authorName,
                authorUsername = write.authorUsername,
                authorAvatarUrl = write.authorAvatarUrl,
                authorBadge = write.authorBadge ?: DEFAULT_BADGE,
                rating = rating,
                title = write.reviewTitle.orEmpty(),
                body = write.reviewBody.orEmpty(),
                updatedAt = now,
                isDeleted = false
            )
            reviewRef.set(review.toMap()).await()
        }
        return write.authorUid
    }

    private fun commentsCollection(mangaId: String, chapterUrl: String?) =
        if (chapterUrl == null) {
            firestore.collection("community_manga").document(mangaId).collection("comments")
        } else {
            firestore.collection("community_manga").document(mangaId)
                .collection("chapters").document(communityChapterKey(chapterUrl))
                .collection("comments")
        }

    private companion object {
        const val DEFAULT_BADGE = "Beginner"
    }
}
