package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityNotificationType
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.model.ReaderReaction
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseCommunityRepository @Inject constructor(
    private val sessionManager: FirebaseSessionManager,
    private val readChapterDao: ReadChapterDao
) : CommunityRepository {

    private val firestore = FirebaseFirestore.getInstance()

    override fun observeMangaComments(mangaId: String): Flow<List<CommunityComment>> =
        observeComments(collectionPath = listOf("community_manga", mangaId, "comments"))

    override fun observeChapterComments(mangaId: String, chapterUrl: String): Flow<List<CommunityComment>> =
        observeComments(collectionPath = listOf("community_manga", mangaId, "chapters", stableChapterKey(chapterUrl), "comments"))

    override fun observeReviews(mangaId: String): Flow<List<MangaReview>> = callbackFlow {
        val reg = firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .orderBy("updatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                val items = snapshot?.documents.orEmpty().mapNotNull { it.toReview() }
                trySend(items)
            }
        awaitClose { reg.remove() }
    }

    override fun observeReaderPresenceCount(mangaId: String, chapterUrl: String): Flow<Int> = callbackFlow {
        val reg = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.size() ?: 0)
            }
        awaitClose { reg.remove() }
    }

    override fun observePageReactions(mangaId: String, chapterUrl: String, pageIndex: Int): Flow<List<ReaderReaction>> = callbackFlow {
        val reg = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("reactions")
            .whereEqualTo("pageIndex", pageIndex)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                val items = snapshot?.documents.orEmpty().mapNotNull { it.toReaction() }
                trySend(items)
            }
        awaitClose { reg.remove() }
    }

    override fun observeNotifications(limit: Int): Flow<List<CommunityNotification>> = callbackFlow {
        val uid = sessionManager.currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = firestore.collection("users").document(uid)
            .collection("notifications")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toNotification() })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun getCurrentProfile(): CommunityProfile? {
        val uid = sessionManager.ensureGuestSession() ?: return null
        val existing = firestore.collection("publicProfiles").document(uid).get().await().toProfile()
        return existing ?: defaultProfile(uid)
    }

    override suspend fun upsertProfile(username: String, bio: String, isPublic: Boolean) {
        val uid = sessionManager.ensureGuestSession() ?: return
        val normalized = username.trim().lowercase()
        require(normalized.isNotBlank()) { "اسم المستخدم مطلوب" }

        val usernameRef = firestore.collection("usernames").document(normalized)
        val currentOwner = usernameRef.get().await().getString("uid")
        require(currentOwner == null || currentOwner == uid) { "اسم المستخدم مستخدم بالفعل" }

        val profile = defaultProfile(uid).copy(
            username = username.trim(),
            bio = bio.trim(),
            isPublic = isPublic,
            updatedAt = System.currentTimeMillis()
        )

        firestore.runBatch { batch ->
            batch.set(firestore.collection("publicProfiles").document(uid), profile.toMap())
            batch.set(usernameRef, mapOf("uid" to uid, "username" to profile.username, "updatedAt" to profile.updatedAt))
        }.await()
    }

    override suspend fun postMangaComment(mangaId: String, slug: String, sourceId: String, text: String, spoiler: Boolean, parentId: String?) {
        postComment(mangaId, slug, sourceId, chapterUrl = null, text = text, spoiler = spoiler, parentId = parentId)
    }

    override suspend fun postChapterComment(mangaId: String, slug: String, sourceId: String, chapterUrl: String, text: String, spoiler: Boolean, parentId: String?) {
        postComment(mangaId, slug, sourceId, chapterUrl = chapterUrl, text = text, spoiler = spoiler, parentId = parentId)
    }

    override suspend fun upsertReview(mangaId: String, slug: String, sourceId: String, rating: Int, title: String, body: String) {
        val profile = currentProfileOrThrow()
        val review = MangaReview(
            id = profile.uid,
            mangaId = mangaId,
            authorUid = profile.uid,
            authorName = profile.username,
            authorAvatarUrl = profile.avatarUrl,
            authorBadge = profile.badgeLabel,
            rating = rating.coerceIn(1, 5),
            title = title.trim(),
            body = body.trim(),
            updatedAt = System.currentTimeMillis()
        )
        firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .document(profile.uid)
            .set(review.toMap())
            .await()
    }

    override suspend fun sendPageReaction(mangaId: String, chapterUrl: String, pageIndex: Int, emoji: String) {
        val profile = currentProfileOrThrow()
        val reaction = ReaderReaction(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            emoji = emoji,
            authorUid = profile.uid,
            authorName = profile.username
        )
        firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("reactions")
            .document(reaction.id)
            .set(reaction.toMap())
            .await()
    }

    override suspend fun setReaderPresence(mangaId: String, chapterUrl: String, active: Boolean) {
        val profile = currentProfileOrThrow()
        val doc = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members")
            .document(profile.uid)
        if (active) {
            doc.set(
                mapOf(
                    "uid" to profile.uid,
                    "username" to profile.username,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
        } else {
            doc.delete().await()
        }
    }

    override suspend fun markNotificationRead(notificationId: String) {
        val uid = sessionManager.currentUserId() ?: return
        firestore.collection("users").document(uid)
            .collection("notifications").document(notificationId)
            .update("read", true)
            .await()
    }

    private suspend fun postComment(
        mangaId: String,
        slug: String,
        sourceId: String,
        chapterUrl: String?,
        text: String,
        spoiler: Boolean,
        parentId: String?
    ) {
        val profile = currentProfileOrThrow()
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "التعليق فارغ" }
        val comment = CommunityComment(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            parentId = parentId,
            authorUid = profile.uid,
            authorName = profile.username,
            authorAvatarUrl = profile.avatarUrl,
            authorBadge = profile.badgeLabel,
            text = trimmed,
            mentions = extractMentions(trimmed),
            spoiler = spoiler,
            createdAt = System.currentTimeMillis()
        )
        val collection = commentsCollection(mangaId, chapterUrl)
        collection.document(comment.id).set(comment.toMap()).await()

        if (parentId != null) {
            runCatching {
                val parent = collection.document(parentId).get().await().toComment()
                if (parent != null && parent.authorUid != profile.uid) {
                    createNotification(
                        targetUid = parent.authorUid,
                        notification = CommunityNotification(
                            id = UUID.randomUUID().toString(),
                            type = CommunityNotificationType.REPLY,
                            title = "رد جديد على تعليقك",
                            body = "${profile.username}: ${trimmed.take(80)}",
                            mangaId = mangaId,
                            slug = slug,
                            sourceId = sourceId,
                            chapterUrl = chapterUrl
                        )
                    )
                }
                collection.document(parentId).update("replyCount", FieldValue.increment(1)).await()
            }
        }

        resolveMentionTargets(comment.mentions).forEach { (uid, username) ->
            if (uid != profile.uid) {
                createNotification(
                    targetUid = uid,
                    notification = CommunityNotification(
                        id = UUID.randomUUID().toString(),
                        type = CommunityNotificationType.MENTION,
                        title = "تمت الإشارة إليك",
                        body = "${profile.username} ذكر ${username}",
                        mangaId = mangaId,
                        slug = slug,
                        sourceId = sourceId,
                        chapterUrl = chapterUrl
                    )
                )
            }
        }
    }

    private suspend fun resolveMentionTargets(mentions: List<String>): List<Pair<String, String>> {
        return mentions.distinct().mapNotNull { username ->
            val doc = firestore.collection("usernames").document(username.lowercase()).get().await()
            val uid = doc.getString("uid") ?: return@mapNotNull null
            uid to (doc.getString("username") ?: username)
        }
    }

    private suspend fun createNotification(targetUid: String, notification: CommunityNotification) {
        firestore.collection("users").document(targetUid)
            .collection("notifications")
            .document(notification.id)
            .set(notification.toMap())
            .await()
    }

    private fun observeComments(collectionPath: List<String>): Flow<List<CommunityComment>> = callbackFlow {
        var ref = firestore.collection(collectionPath.first())
        var docRef: com.google.firebase.firestore.DocumentReference? = null
        // build path alternating collection/document
        docRef = firestore.collection(collectionPath[0]).document(collectionPath[1])
        if (collectionPath.size == 3) {
            ref = docRef.collection(collectionPath[2])
        } else {
            ref = docRef.collection(collectionPath[2]).document(collectionPath[3]).collection(collectionPath[4])
        }
        val reg = ref.orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toComment() })
            }
        awaitClose { reg.remove() }
    }

    private fun commentsCollection(mangaId: String, chapterUrl: String?) =
        if (chapterUrl == null) {
            firestore.collection("community_manga").document(mangaId).collection("comments")
        } else {
            firestore.collection("community_manga").document(mangaId)
                .collection("chapters").document(stableChapterKey(chapterUrl))
                .collection("comments")
        }

    private suspend fun currentProfileOrThrow(): CommunityProfile {
        val uid = sessionManager.ensureGuestSession() ?: error("No user")
        return getCurrentProfile() ?: defaultProfile(uid)
    }

    private suspend fun defaultProfile(uid: String): CommunityProfile {
        val firebaseUser = sessionManager.currentUser()
        val readCount = readChapterDao.getTotalReadCount()
        val badge = when {
            readCount >= 1000 -> "Pirate King"
            readCount >= 400 -> "Avid Reader"
            readCount >= 150 -> "Shonen Specialist"
            else -> "Beginner"
        }
        return CommunityProfile(
            uid = uid,
            username = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: "reader_${uid.takeLast(6)}",
            avatarUrl = firebaseUser?.photoUrl?.toString(),
            badgeLabel = badge,
            isPublic = true,
            bio = ""
        )
    }

    private fun threadId(mangaId: String, chapterUrl: String): String = "$mangaId-${stableChapterKey(chapterUrl)}"

    private fun stableChapterKey(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun extractMentions(text: String): List<String> = Regex("@([A-Za-z0-9_]{3,30})")
        .findAll(text)
        .map { it.groupValues[1] }
        .toList()

    private fun CommunityProfile.toMap() = mapOf(
        "uid" to uid,
        "username" to username,
        "avatarUrl" to avatarUrl,
        "badgeLabel" to badgeLabel,
        "isPublic" to isPublic,
        "bio" to bio,
        "updatedAt" to updatedAt
    )

    private fun CommunityComment.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "chapterUrl" to chapterUrl,
        "parentId" to parentId,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorAvatarUrl" to authorAvatarUrl,
        "authorBadge" to authorBadge,
        "text" to text,
        "mentions" to mentions,
        "spoiler" to spoiler,
        "createdAt" to createdAt,
        "replyCount" to replyCount
    )

    private fun MangaReview.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorAvatarUrl" to authorAvatarUrl,
        "authorBadge" to authorBadge,
        "rating" to rating,
        "title" to title,
        "body" to body,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    private fun ReaderReaction.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "chapterUrl" to chapterUrl,
        "pageIndex" to pageIndex,
        "emoji" to emoji,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "createdAt" to createdAt
    )

    private fun CommunityNotification.toMap() = mapOf(
        "id" to id,
        "type" to type.name,
        "title" to title,
        "body" to body,
        "mangaId" to mangaId,
        "slug" to slug,
        "sourceId" to sourceId,
        "chapterUrl" to chapterUrl,
        "createdAt" to createdAt,
        "read" to read
    )

    private fun DocumentSnapshot.toProfile(): CommunityProfile? = runCatching {
        CommunityProfile(
            uid = getString("uid") ?: id,
            username = getString("username") ?: return null,
            avatarUrl = getString("avatarUrl"),
            badgeLabel = getString("badgeLabel") ?: "Beginner",
            isPublic = getBoolean("isPublic") ?: true,
            bio = getString("bio") ?: "",
            updatedAt = getLong("updatedAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toComment(): CommunityComment? = runCatching {
        CommunityComment(
            id = getString("id") ?: id,
            mangaId = getString("mangaId") ?: return null,
            chapterUrl = getString("chapterUrl"),
            parentId = getString("parentId"),
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            authorAvatarUrl = getString("authorAvatarUrl"),
            authorBadge = getString("authorBadge") ?: "Beginner",
            text = getString("text") ?: return null,
            mentions = (get("mentions") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
            spoiler = getBoolean("spoiler") ?: false,
            createdAt = getLong("createdAt") ?: 0L,
            replyCount = (getLong("replyCount") ?: 0L).toInt()
        )
    }.getOrNull()

    private fun DocumentSnapshot.toReview(): MangaReview? = runCatching {
        MangaReview(
            id = getString("id") ?: id,
            mangaId = getString("mangaId") ?: return null,
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            authorAvatarUrl = getString("authorAvatarUrl"),
            authorBadge = getString("authorBadge") ?: "Beginner",
            rating = (getLong("rating") ?: 0L).toInt(),
            title = getString("title") ?: "",
            body = getString("body") ?: "",
            createdAt = getLong("createdAt") ?: 0L,
            updatedAt = getLong("updatedAt") ?: getLong("createdAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toReaction(): ReaderReaction? = runCatching {
        ReaderReaction(
            id = getString("id") ?: id,
            mangaId = getString("mangaId") ?: return null,
            chapterUrl = getString("chapterUrl") ?: return null,
            pageIndex = (getLong("pageIndex") ?: 0L).toInt(),
            emoji = getString("emoji") ?: return null,
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            createdAt = getLong("createdAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toNotification(): CommunityNotification? = runCatching {
        CommunityNotification(
            id = getString("id") ?: id,
            type = getString("type")?.let { CommunityNotificationType.valueOf(it) } ?: CommunityNotificationType.REPLY,
            title = getString("title") ?: return null,
            body = getString("body") ?: return null,
            mangaId = getString("mangaId") ?: return null,
            slug = getString("slug") ?: "",
            sourceId = getString("sourceId") ?: "azora",
            chapterUrl = getString("chapterUrl"),
            createdAt = getLong("createdAt") ?: 0L,
            read = getBoolean("read") ?: false
        )
    }.getOrNull()
}
