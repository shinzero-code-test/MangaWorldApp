package com.exapps.mangaworld.core.firebase

import android.content.Context
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityNotificationType
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.ModerationReport
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.model.CustomUserListItem
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.model.ReaderReaction
import com.exapps.mangaworld.domain.model.UserFollow
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val DASHBOARD_BASE_URL = "https://mangaworld-admin.vercel.app"
private const val MAX_COMMUNITY_MENTIONS = 10

/** Username must be 3-20 chars: alphanumeric and underscores only, no leading/trailing underscores. */
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$")

@Singleton
class FirebaseCommunityRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager,
    private val readChapterDao: ReadChapterDao,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val achievementManager: com.exapps.mangaworld.core.data.AchievementManager,
    private val settingsRepository: com.exapps.mangaworld.domain.repository.SettingsRepository
) : CommunityRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val realtimeDb = FirebaseDatabase.getInstance()

    override fun observeMangaComments(mangaId: String): Flow<List<CommunityComment>> =
        observeComments(commentsCollection(mangaId, null))

    override fun observeChapterComments(mangaId: String, chapterUrl: String): Flow<List<CommunityComment>> =
        observeComments(commentsCollection(mangaId, chapterUrl))

    override fun observeReviews(mangaId: String): Flow<List<MangaReview>> = callbackFlow {
        val reg = firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toReview() })
            }
        awaitClose { reg.remove() }
    }

    override fun observeReaderPresenceCount(mangaId: String, chapterUrl: String): Flow<Int> = callbackFlow {
        val members = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members")
        // Bounded listener: presence is a live-reader *hint*, so counts saturate
        // at 100 rather than downloading an unbounded subcollection per keystroke.
        val reg = members.limit(100).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            trySend(snapshot?.size() ?: 0)
        }
        awaitClose { reg.remove() }
    }

    override fun observePageReactions(mangaId: String, chapterUrl: String, pageIndex: Int): Flow<List<ReaderReaction>> = callbackFlow {
        val reg = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("reactions")
            .whereEqualTo("pageIndex", pageIndex)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toReaction() })
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
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toNotification() })
            }
        awaitClose { reg.remove() }
    }

    override fun observeChatMessages(roomId: String): Flow<List<CommunityChatMessage>> = callbackFlow {
        val ref = realtimeDb.getReference("chatRooms").child(roomId).child("messages")
        // Download only the most recent window — a full-history value listener made
        // every chat open cost O(all messages) bandwidth (H-review).
        val query = ref.orderByKey().limitToLast(100)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = snapshot.children.mapNotNull { child ->
                    (child.value as? Map<String, Any?>)?.toChatMessage(child.key.orEmpty())
                }.sortedBy { it.createdAt }
                trySend(messages)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        query.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    override fun observeUserLists(): Flow<List<CustomUserList>> = callbackFlow {
        val uid = sessionManager.currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = firestore.collection("users").document(uid).collection("lists")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserList() })
            }
        awaitClose { reg.remove() }
    }

    override fun observePublicProfile(userId: String): Flow<CommunityProfile?> = callbackFlow {
        val reg = firestore.collection("publicProfiles").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.toProfile())
            }
        awaitClose { reg.remove() }
    }

    override fun observePublicLists(userId: String): Flow<List<CustomUserList>> = callbackFlow {
        val reg = firestore.collection("users").document(userId).collection("lists")
            .whereEqualTo("isPublic", true)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserList() })
            }
        awaitClose { reg.remove() }
    }

    override fun observePublicActivity(userId: String): Flow<List<CommunityComment>> = callbackFlow {
        val reg = firestore.collectionGroup("comments")
            .whereEqualTo("authorUid", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toComment() })
            }
        awaitClose { reg.remove() }
    }

    override fun observeModerationReports(): Flow<List<ModerationReport>> = callbackFlow {
        val reg = firestore.collection("moderationReports")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toModerationReport() })
            }
        awaitClose { reg.remove() }
    }

    override fun observeListItems(listId: String): Flow<List<CustomUserListItem>> = callbackFlow {
        val uid = sessionManager.currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = firestore.collection("users").document(uid)
            .collection("lists").document(listId)
            .collection("items")
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserListItem() })
            }
        awaitClose { reg.remove() }
    }

    override fun observePublicListItems(userId: String, listId: String): Flow<List<CustomUserListItem>> = callbackFlow {
        val reg = firestore.collection("users").document(userId)
            .collection("lists").document(listId)
            .collection("items")
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserListItem() })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun getCurrentProfile(): CommunityProfile? {
        val uid = sessionManager.ensureFirebaseSession() ?: return null
        val existing = firestore.collection("publicProfiles").document(uid).get().await().toProfile()
        return existing ?: defaultProfile(uid)
    }

    override suspend fun upsertProfile(username: String, bio: String, isPublic: Boolean, avatarUrl: String?, bannerUrl: String?, displayName: String) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val normalized = username.trim().lowercase()
        require(normalized.isNotBlank()) { context.getString(R.string.auth_error_username_required) }
        require(normalized.length in 3..20) { context.getString(R.string.auth_error_username_invalid) }
        require(USERNAME_REGEX.matches(normalized)) { context.getString(R.string.auth_error_username_invalid) }

        val existing = getCurrentProfile() ?: defaultProfile(uid)
        // Recalculate badge based on current achievements
        val newBadge = try { achievementManager.calculateBadge() } catch (_: Exception) { existing.badgeLabel }
        val profile = existing.copy(
            username = username.trim(),
            displayName = displayName.trim().ifBlank { existing.displayName }.ifBlank { username.trim() },
            bio = bio.trim(),
            isPublic = isPublic,
            badgeLabel = newBadge,
            avatarUrl = avatarUrl ?: existing.avatarUrl,
            bannerUrl = bannerUrl ?: existing.bannerUrl,
            updatedAt = System.currentTimeMillis()
        )

        val usernameRef = firestore.collection("usernames").document(normalized)
        val profileRef = firestore.collection("publicProfiles").document(uid)
        firestore.runTransaction { transaction ->
            // If username changed, check uniqueness and clean up old username doc
            val oldNormalized = existing.username.trim().lowercase()
            if (oldNormalized != normalized && oldNormalized.isNotBlank()) {
                val oldUsernameOwner = transaction.get(firestore.collection("usernames").document(oldNormalized)).getString("uid")
                if (oldUsernameOwner == uid) {
                    transaction.delete(firestore.collection("usernames").document(oldNormalized))
                }
            }
            val currentOwner = transaction.get(usernameRef).getString("uid")
            require(currentOwner == null || currentOwner == uid) { context.getString(R.string.auth_error_username_taken) }
            // Use SetOptions.merge to update existing doc, never create a separate one
            transaction.set(profileRef, profile.toEditableMap(), SetOptions.merge())
            transaction.set(
                usernameRef,
                mapOf("uid" to uid, "username" to profile.username, "updatedAt" to profile.updatedAt)
            )
        }.await()

        // Propagate the denormalized author snapshot without exceeding Firestore's 500-write batch limit.
        try {
            propagateAuthorSnapshot(profile)
        } catch (throwable: kotlinx.coroutines.CancellationException) {
            throw throwable
        } catch (_: Exception) {
            // The profile itself is already saved. A later profile update can retry this best-effort sync.
        }
    }

    override suspend fun updateProfilePrivacy(showListsPublic: Boolean, showActivityPublic: Boolean, showLibraryPublic: Boolean) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        firestore.collection("publicProfiles").document(uid).set(
            mapOf(
                "showListsPublic" to showListsPublic,
                "showActivityPublic" to showActivityPublic,
                "showLibraryPublic" to showLibraryPublic,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun createOrUpdateList(listId: String?, name: String, description: String, coverUrl: String, rating: Float, genres: List<String>, isPublic: Boolean): String {
        val uid = sessionManager.ensureFirebaseSession() ?: error(context.getString(R.string.community_error_sign_in))
        val id = listId ?: UUID.randomUUID().toString()
        val doc = firestore.collection("users").document(uid).collection("lists").document(id)
        // Server-side count — the old full-collection download existed only for a number.
        val existingCount = runCatching {
            doc.collection("items").count().get(AggregateSource.SERVER).await().count
        }.getOrDefault(0L).toInt()
        val payload = CustomUserList(
            id = id,
            name = name.trim(),
            description = description.trim(),
            coverUrl = coverUrl,
            rating = rating,
            genres = genres,
            isPublic = isPublic,
            itemCount = existingCount,
            updatedAt = System.currentTimeMillis()
        )
        doc.set(payload.toMap()).await()
        return id
    }

    override suspend fun deleteList(listId: String) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val doc = firestore.collection("users").document(uid).collection("lists").document(listId)
        val items = doc.collection("items").get().await().documents
        // Firestore batches cap at 500 operations — chunk so large lists can
        // actually be deleted (M-review; mirrors FirebaseSyncManager.commitChunked).
        (items.map { it.reference } + doc).chunked(400).forEach { chunk ->
            firestore.runBatch { batch ->
                chunk.forEach(batch::delete)
            }.await()
        }
    }

    override suspend fun addMangaToList(listId: String, item: CustomUserListItem) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val listDoc = firestore.collection("users").document(uid).collection("lists").document(listId)
        val itemDoc = listDoc.collection("items").document(item.mangaId)
        val existed = itemDoc.get().await().exists()
        itemDoc.set(item.toMap()).await()
        listDoc.update(
            mapOf(
                "itemCount" to FieldValue.increment(if (existed) 0 else 1),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun removeMangaFromList(listId: String, mangaId: String) {
        val uid = sessionManager.ensureFirebaseSession() ?: return
        val listDoc = firestore.collection("users").document(uid).collection("lists").document(listId)
        val itemDoc = listDoc.collection("items").document(mangaId)
        // Mirror addMangaToList: only decrement when the item actually existed,
        // otherwise repeated removes drive itemCount negative (L-review).
        val existed = itemDoc.get().await().exists()
        if (!existed) return
        itemDoc.delete().await()
        listDoc.update(
            mapOf(
                "itemCount" to FieldValue.increment(-1),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun postMangaComment(mangaId: String, slug: String, sourceId: String, text: String, spoiler: Boolean, replyTarget: com.exapps.mangaworld.domain.model.CommunityReplyTarget?) {
        postComment(mangaId, slug, sourceId, null, text, spoiler, replyTarget)
    }

    override suspend fun postChapterComment(mangaId: String, slug: String, sourceId: String, chapterUrl: String, text: String, spoiler: Boolean, replyTarget: com.exapps.mangaworld.domain.model.CommunityReplyTarget?) {
        postComment(mangaId, slug, sourceId, chapterUrl, text, spoiler, replyTarget)
    }

    override suspend fun upsertReview(mangaId: String, slug: String, sourceId: String, rating: Int, title: String, body: String) {
        val profile = currentProfileOrThrow()
        val trimmedTitle = title.trim()
        val trimmedBody = body.trim()
        require(trimmedTitle.isNotBlank() || trimmedBody.isNotBlank()) {
            context.getString(R.string.community_error_empty_content)
        }
        validateModeration(trimmedTitle)
        validateModeration(trimmedBody)
        val now = System.currentTimeMillis()
        val reviewRef = firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .document(profile.uid)
        val existingReview = reviewRef.get().await()

        // v8 (#7/#8): one code path for create/edit/RECREATE. A soft-deleted
        // review is resurrected in place (rules permit exactly this transition),
        // so users can write a new review after deleting their old one instead
        // of hitting the previous dead-end error.
        if (existingReview.exists()) {
            reviewRef.update(
                mapOf(
                    "rating" to rating.coerceIn(1, 5),
                    "title" to trimmedTitle,
                    "body" to trimmedBody,
                    // Refresh the denormalized author snapshot so the card always
                    // matches the current public profile (allowed by rules).
                    "authorName" to (profile.displayName.ifBlank { profile.username }),
                    "authorUsername" to profile.username,
                    "authorAvatarUrl" to profile.avatarUrl,
                    "isDeleted" to false,
                    "updatedAt" to now
                )
            ).await()
        } else {
            val review = MangaReview(
                id = profile.uid,
                mangaId = mangaId,
                authorUid = profile.uid,
                authorName = profile.displayName.ifBlank { profile.username },
                authorUsername = profile.username,
                authorAvatarUrl = profile.avatarUrl,
                authorBadge = profile.badgeLabel,
                rating = rating.coerceIn(1, 5),
                title = trimmedTitle,
                body = trimmedBody,
                updatedAt = now,
                isDeleted = false
            )
            reviewRef.set(review.toMap()).await()
        }
    }

    override suspend fun updateComment(comment: CommunityComment, text: String, spoiler: Boolean) {
        val profile = currentProfileOrThrow()
        require(profile.uid == comment.authorUid) { context.getString(R.string.community_error_author_edit) }
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { context.getString(R.string.community_error_empty_content) }
        validateModeration(trimmed)
        commentsCollection(comment.mangaId, comment.chapterUrl).document(comment.id)
            .update(
                mapOf(
                    "text" to trimmed,
                    "spoiler" to spoiler,
                    "mentions" to extractMentions(trimmed),
                    "editedAt" to System.currentTimeMillis()
                )
            )
            .await()
    }

    override suspend fun deleteComment(comment: CommunityComment) {
        val profile = currentProfileOrThrow()
        require(profile.uid == comment.authorUid) { context.getString(R.string.community_error_author_delete) }
        val docRef = commentsCollection(comment.mangaId, comment.chapterUrl).document(comment.id)
        if (comment.replyCount > 0) {
            // Referenced by replies — retain the anchor document so the thread
            // stays navigable; content is blanked and flagged.
            docRef.update(
                mapOf(
                    "text" to "",
                    "mentions" to emptyList<String>(),
                    "isDeleted" to true,
                    "editedAt" to System.currentTimeMillis()
                )
            ).await()
        } else {
            // v8 (#5): unreferenced content is removed PERMANENTLY instead of
            // leaving a "[deleted]" husk behind.
            docRef.delete().await()
        }
    }

    override suspend fun deleteReview(review: MangaReview) {
        val profile = currentProfileOrThrow()
        require(profile.uid == review.authorUid) { context.getString(R.string.community_error_author_delete) }
        val reviewRef = firestore.collection("community_manga").document(review.mangaId)
            .collection("reviews").document(review.id)
        if (review.replyCount > 0) {
            // Retain the review document to preserve its replies thread.
            reviewRef.update(
                mapOf(
                    "title" to "",
                    "body" to "",
                    "isDeleted" to true,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
        } else {
            // v8 (#5): permanent removal when nothing references it. Also frees
            // the one-review-per-user slot for a fresh write (v8 #8).
            reviewRef.delete().await()
        }
    }

    override suspend fun sendPageReaction(mangaId: String, chapterUrl: String, pageIndex: Int, emoji: String, normalizedX: Float, normalizedY: Float) {
        val profile = currentProfileOrThrow()
        val reaction = ReaderReaction(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            emoji = emoji,
            authorUid = profile.uid,
            authorName = profile.displayName.ifBlank { profile.username },
            normalizedX = normalizedX.coerceIn(0f, 1f),
            normalizedY = normalizedY.coerceIn(0f, 1f)
        )
        firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("reactions").document(reaction.id)
            .set(reaction.toMap())
            .await()
    }

    override suspend fun sendChatMessage(roomId: String, text: String) {
        // Chat is named-users-only. Checking BEFORE currentProfileOrThrow() matters:
        // that helper silently provisions an anonymous session, which the RTDB rules
        // now reject — guests must get a sign-in error, not a provisioned identity.
        val currentUser = sessionManager.currentUser()
        if (currentUser == null || currentUser.isAnonymous) {
            error(context.getString(R.string.community_error_sign_in))
        }
        val profile = currentProfileOrThrow()
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { context.getString(R.string.community_error_empty_message) }
        require(trimmed.length <= 500) { context.getString(R.string.community_error_banned_content) }
        validateModeration(trimmed)
        val ref = realtimeDb.getReference("chatRooms").child(roomId).child("messages").push()
        val message = CommunityChatMessage(
            id = ref.key ?: UUID.randomUUID().toString(),
            roomId = roomId,
            authorUid = profile.uid,
            authorName = profile.displayName.ifBlank { profile.username },
            authorBadge = profile.badgeLabel,
            text = trimmed,
            createdAt = System.currentTimeMillis()
        )
        ref.setValue(message.toMap()).await()
    }

    override suspend fun reportComment(comment: CommunityComment, reason: String) {
        reportContent(
            targetId = comment.id,
            targetType = "comment",
            mangaId = comment.mangaId,
            chapterUrl = comment.chapterUrl,
            reportedUid = comment.authorUid,
            reason = reason
        )
    }

    override suspend fun reportReview(review: MangaReview, reason: String) {
        reportContent(
            targetId = review.id,
            targetType = "review",
            mangaId = review.mangaId,
            chapterUrl = null,
            reportedUid = review.authorUid,
            reason = reason
        )
    }

    override suspend fun likeComment(commentId: String) {
        voteOnContent(targetType = "comment", mangaId = null, targetId = commentId, vote = 1)
    }

    override suspend fun dislikeComment(commentId: String) {
        voteOnContent(targetType = "comment", mangaId = null, targetId = commentId, vote = -1)
    }

    override suspend fun likeReview(mangaId: String, reviewId: String) {
        voteOnContent(targetType = "review", mangaId = mangaId, targetId = reviewId, vote = 1)
    }

    override suspend fun dislikeReview(mangaId: String, reviewId: String) {
        voteOnContent(targetType = "review", mangaId = mangaId, targetId = reviewId, vote = -1)
    }

    override suspend fun setReaderPresence(mangaId: String, chapterUrl: String, active: Boolean) {
        val profile = currentProfileOrThrow()
        val memberDoc = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members").document(profile.uid)
        if (active) {
            memberDoc.set(
                mapOf(
                    "uid" to profile.uid,
                    "username" to profile.username,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
        } else {
            memberDoc.delete().await()
        }
    }

    override suspend fun markNotificationRead(notificationId: String) {
        val uid = sessionManager.currentUserId() ?: return
        firestore.collection("users").document(uid)
            .collection("notifications").document(notificationId)
            .update("read", true)
            .await()
    }

    override suspend fun markNotificationsRead(ids: List<String>) {
        val uid = sessionManager.currentUserId() ?: return
        // WriteBatch chunks — one round-trip per 400 instead of one per item.
        ids.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { id ->
                batch.update(
                    firestore.collection("users").document(uid)
                        .collection("notifications").document(id),
                    mapOf("read" to true)
                )
            }
            batch.commit().await()
        }
    }

    private fun observeComments(collection: com.google.firebase.firestore.CollectionReference): Flow<List<CommunityComment>> = callbackFlow {
        val reg = collection.orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toComment() })
            }
        awaitClose { reg.remove() }
    }

    private suspend fun postComment(
        mangaId: String,
        slug: String,
        sourceId: String,
        chapterUrl: String?,
        text: String,
        spoiler: Boolean,
        replyTarget: com.exapps.mangaworld.domain.model.CommunityReplyTarget?
    ) {
        val profile = currentProfileOrThrow()
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { context.getString(R.string.community_error_empty_content) }
        validateModeration(trimmed)
        val comment = CommunityComment(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            slug = slug,
            sourceId = sourceId,
            parentId = replyTarget?.parentId,
            threadRootId = replyTarget?.parentId,
            reviewId = replyTarget?.reviewId,
            replyToUid = replyTarget?.replyToUid,
            replyToUsername = replyTarget?.replyToUsername,
            authorUid = profile.uid,
            authorName = profile.displayName.ifBlank { profile.username },
            authorUsername = profile.username,
            authorAvatarUrl = profile.avatarUrl,
            authorBadge = profile.badgeLabel,
            text = trimmed,
            mentions = extractMentions(trimmed),
            spoiler = spoiler,
            createdAt = System.currentTimeMillis()
        )
        val collection = commentsCollection(mangaId, chapterUrl)
        collection.document(comment.id).set(comment.toMap()).await()
        if (replyTarget != null || comment.mentions.isNotEmpty()) {
            sendPushNotification(mangaId, chapterUrl, comment.id)
        }
    }

    private suspend fun sendPushNotification(mangaId: String, chapterUrl: String?, commentId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = sessionManager.currentIdToken() ?: return@withContext
                val body = org.json.JSONObject().apply {
                    put("mangaId", mangaId)
                    put("commentId", commentId)
                    if (chapterUrl != null) put("chapterUrl", chapterUrl)
                }
                val conn = java.net.URL("$DASHBOARD_BASE_URL/api/notifications/push-reply").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
                    conn.responseCode // just trigger the request
                } finally {
                    conn.disconnect()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Push notifications are best-effort — never fail the parent write.
                Unit
            }
        }

    private suspend fun voteOnContent(targetType: String, mangaId: String?, targetId: String, vote: Int) {
        require(targetId.isNotBlank()) { context.getString(R.string.community_error_invalid_content) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val token = sessionManager.currentIdToken() ?: error(context.getString(R.string.community_error_sign_in))
            val body = org.json.JSONObject().apply {
                put("targetType", targetType)
                put("targetId", targetId)
                if (mangaId != null) put("mangaId", mangaId)
                put("vote", vote)
            }
            val conn = java.net.URL("$DASHBOARD_BASE_URL/api/community/vote").openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
                check(conn.responseCode in 200..299) { context.getString(R.string.community_error_vote) }
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun reportContent(
        targetId: String,
        targetType: String,
        mangaId: String,
        chapterUrl: String?,
        reportedUid: String,
        reason: String
    ) {
        val reporter = currentProfileOrThrow()
        val trimmedReason = reason.trim()
        require(trimmedReason.isNotBlank()) { context.getString(R.string.community_error_report_reason_required) }
        val reportId = UUID.randomUUID().toString()
        firestore.collection("moderationReports").document(reportId)
            .set(
                mapOf(
                    "id" to reportId,
                    // Keep commentId for older releases while exposing the typed name to new consumers.
                    "commentId" to targetId,
                    "targetId" to targetId,
                    "targetType" to targetType,
                    "mangaId" to mangaId,
                    "chapterUrl" to chapterUrl,
                    "reportedUid" to reportedUid,
                    "reporterUid" to reporter.uid,
                    "reason" to trimmedReason,
                    "createdAt" to System.currentTimeMillis(),
                    "status" to "open"
                )
            )
            .await()
    }

    private suspend fun currentProfileOrThrow(): CommunityProfile {
        val uid = sessionManager.ensureFirebaseSession() ?: error(context.getString(R.string.community_error_sign_in))
        return getCurrentProfile() ?: defaultProfile(uid)
    }

    private suspend fun defaultProfile(uid: String): CommunityProfile {
        val firebaseUser = sessionManager.currentUser()
        // Use AchievementManager for consistent badge calculation
        val badge = try {
            achievementManager.calculateBadge()
        } catch (_: Exception) {
            val readCount = readChapterDao.getTotalReadCount()
            when {
                readCount >= 1000 -> "Pirate King"
                readCount >= 400 -> "Avid Reader"
                readCount >= 150 -> "Shonen Specialist"
                readCount >= 50 -> "Manga Enthusiast"
                readCount >= 10 -> "Chapter Hunter"
                else -> "Beginner"
            }
        }
        val displayName = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: ""
        return CommunityProfile(
            uid = uid,
            username = "",  // Must be set via upsertProfile during signup
            displayName = displayName,
            avatarUrl = firebaseUser?.photoUrl?.toString(),
            badgeLabel = badge,
            role = "viewer",
            isPublic = true,
            showListsPublic = true,
            showActivityPublic = true,
            showLibraryPublic = true,
            bio = ""
        )
    }

    private fun commentsCollection(mangaId: String, chapterUrl: String?) =
        if (chapterUrl == null) {
            firestore.collection("community_manga").document(mangaId).collection("comments")
        } else {
            firestore.collection("community_manga").document(mangaId)
                .collection("chapters").document(stableChapterKey(chapterUrl))
                .collection("comments")
        }

    private fun threadId(mangaId: String, chapterUrl: String): String = "$mangaId-${stableChapterKey(chapterUrl)}"

    /** 24 hex chars (96 bits) — matches the dashboard's stableChapterKey. Do NOT change without updating both Kotlin and TypeScript. */
    private fun stableChapterKey(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun extractMentions(text: String): List<String> = Regex("@([A-Za-z0-9_]{3,30})")
        .findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .take(MAX_COMMUNITY_MENTIONS)
        .toList()

    private suspend fun propagateAuthorSnapshot(profile: CommunityProfile) {
        val authorFields = mapOf<String, Any?>(
            "authorName" to profile.displayName.ifBlank { profile.username },
            "authorUsername" to profile.username,
            "authorAvatarUrl" to profile.avatarUrl
        )
        val commentReferences = collectAuthorContentRefs(profile.uid, "comments")
        val reviewReferences = collectAuthorContentRefs(profile.uid, "reviews")

        // Rules read the single public-profile document to validate each author snapshot. Keep
        // batches safely below Firestore's 20 document-access-call ceiling even if rule reads
        // are not cached across writes.
        (commentReferences + reviewReferences)
            .chunked(15)
            .forEach { references ->
                firestore.runBatch { batch ->
                    references.forEach { reference -> batch.update(reference, authorFields) }
                }.await()
            }
    }

    /**
     * Paginated collectionGroup fetch of one author's content, hard-capped so a
     * prolific author cannot turn a profile rename into an unbounded read burst
     * (H-review). 300-page batches × 10 pages ≈ 3 000 docs per collection.
     */
    private suspend fun collectAuthorContentRefs(uid: String, collection: String): List<DocumentReference> {
        val references = mutableListOf<DocumentReference>()
        var lastUpdatedAt: Long? = null
        var pages = 0
        do {
            var query: Query = firestore.collectionGroup(collection)
                .whereEqualTo("authorUid", uid)
                .orderBy("updatedAt")
                .limit(300)
            lastUpdatedAt?.let { ts -> query = query.startAfter(ts) }
            val snapshot = query.get().await()
            references += snapshot.documents.map { it.reference }
            lastUpdatedAt = snapshot.documents.lastOrNull()?.getLong("updatedAt")
            pages++
        } while (snapshot.size() == 300 && pages < 10)
        return references
    }

    /**
     * Content gate for every community write.
     *
     * Layer 1 (local): Remote Config keywords — fast, works offline, trivially
     * bypassed by a tampered client. Layer 2 (server): the dashboard's
     * /api/community/moderate re-checks the published keywords with the Admin
     * SDK, which a repackaged APK cannot skip (H-review). Server check is
     * fail-open so an outage can't silence the whole community; post-hoc
     * moderationReports remain the backstop.
     */
    private suspend fun validateModeration(text: String) {
        val banned = remoteConfigManager.bannedKeywords.value
        require(!containsBannedKeyword(text, banned)) {
            context.getString(R.string.community_error_banned_content)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = sessionManager.currentIdToken() ?: return@withContext
                val body = org.json.JSONObject().apply { put("text", text) }
                val conn = java.net.URL("$DASHBOARD_BASE_URL/api/community/moderate").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val response = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                        require(response.optBoolean("allowed", true)) {
                            context.getString(R.string.community_error_banned_content)
                        }
                    }
                    // Non-2xx → fail-open (server outage must not block posting).
                } finally {
                    conn.disconnect()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Network/parse failure → fail-open, matching the endpoint's contract.
            }
        }
    }

    private fun CommunityProfile.toEditableMap() = mapOf(
        "username" to username,
        "displayName" to displayName,
        "avatarUrl" to avatarUrl,
        "bannerUrl" to bannerUrl,
        "badgeLabel" to badgeLabel,
        "isPublic" to isPublic,
        "showListsPublic" to showListsPublic,
        "showActivityPublic" to showActivityPublic,
        "showLibraryPublic" to showLibraryPublic,
        "bio" to bio,
        "updatedAt" to updatedAt
    )

    private fun CustomUserList.toMap() = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "coverUrl" to coverUrl,
        "rating" to rating,
        "genres" to genres,
        "isPublic" to isPublic,
        "itemCount" to itemCount,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    private fun CustomUserListItem.toMap() = mapOf(
        "mangaId" to mangaId,
        "sourceId" to sourceId,
        "slug" to slug,
        "title" to title,
        "coverUrl" to coverUrl,
        "rating" to rating,
        "genres" to genres,
        "addedAt" to addedAt
    )

    private fun CommunityComment.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "chapterUrl" to chapterUrl,
        "slug" to slug,
        "sourceId" to sourceId,
        "parentId" to parentId,
        "threadRootId" to threadRootId,
        "reviewId" to reviewId,
        "replyToUid" to replyToUid,
        "replyToUsername" to replyToUsername,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorUsername" to authorUsername,
        "authorAvatarUrl" to authorAvatarUrl,
        "authorBadge" to authorBadge,
        "text" to text,
        "mentions" to mentions,
        "spoiler" to spoiler,
        "isDeleted" to isDeleted,
        "editedAt" to editedAt,
        "reportedCount" to reportedCount,
        "createdAt" to createdAt,
        "replyCount" to replyCount,
        "likes" to likes,
        "dislikes" to dislikes
    )

    private fun MangaReview.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorUsername" to authorUsername,
        "authorAvatarUrl" to authorAvatarUrl,
        "authorBadge" to authorBadge,
        "rating" to rating,
        "title" to title,
        "body" to body,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "replyCount" to replyCount,
        "likes" to likes,
        "dislikes" to dislikes,
        "reportedCount" to reportedCount,
        "isDeleted" to isDeleted
    )

    private fun ReaderReaction.toMap() = mapOf(
        "id" to id,
        "mangaId" to mangaId,
        "chapterUrl" to chapterUrl,
        "pageIndex" to pageIndex,
        "emoji" to emoji,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "normalizedX" to normalizedX,
        "normalizedY" to normalizedY,
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
        "commentId" to commentId,
        "createdAt" to createdAt,
        "read" to read
    )

    private fun CommunityChatMessage.toMap() = mapOf(
        "roomId" to roomId,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorBadge" to authorBadge,
        "text" to text,
        "createdAt" to createdAt
    )

    private fun DocumentSnapshot.toProfile(): CommunityProfile? = runCatching {
        CommunityProfile(
            uid = getString("uid") ?: id,
            username = getString("username") ?: return null,
            displayName = getString("displayName") ?: "",
            avatarUrl = getString("avatarUrl"),
            bannerUrl = getString("bannerUrl"),
            badgeLabel = getString("badgeLabel") ?: "Beginner",
            role = getString("role") ?: "viewer",
            isPublic = getBoolean("isPublic") ?: true,
            showListsPublic = getBoolean("showListsPublic") ?: true,
            showActivityPublic = getBoolean("showActivityPublic") ?: true,
            showLibraryPublic = getBoolean("showLibraryPublic") ?: true,
            bio = getString("bio") ?: "",
            updatedAt = getLong("updatedAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toComment(): CommunityComment? = runCatching {
        CommunityComment(
            id = getString("id") ?: id,
            mangaId = getString("mangaId") ?: return null,
            chapterUrl = getString("chapterUrl"),
            slug = getString("slug") ?: "",
            sourceId = getString("sourceId") ?: "",
            parentId = getString("parentId"),
            threadRootId = getString("threadRootId"),
            reviewId = getString("reviewId"),
            replyToUid = getString("replyToUid"),
            replyToUsername = getString("replyToUsername"),
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            authorUsername = getString("authorUsername") ?: "",
            authorAvatarUrl = getString("authorAvatarUrl") ?: getString("authorPhotoUrl"),
            authorBadge = getString("authorBadge") ?: "Beginner",
            text = getString("text") ?: return null,
            mentions = (get("mentions") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
            spoiler = getBoolean("spoiler") ?: false,
            isDeleted = getBoolean("isDeleted") ?: false,
            editedAt = getLong("editedAt"),
            reportedCount = (getLong("reportedCount") ?: 0L).toInt(),
            createdAt = getLong("createdAt") ?: 0L,
            replyCount = (getLong("replyCount") ?: 0L).toInt(),
            likes = (getLong("likes") ?: 0L).toInt(),
            dislikes = (getLong("dislikes") ?: 0L).toInt()
        )
    }.getOrNull()

    private fun DocumentSnapshot.toReview(): MangaReview? = runCatching {
        MangaReview(
            id = getString("id") ?: id,
            mangaId = getString("mangaId") ?: return null,
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            authorUsername = getString("authorUsername") ?: "",
            authorAvatarUrl = getString("authorAvatarUrl") ?: getString("authorPhotoUrl"),
            authorBadge = getString("authorBadge") ?: "Beginner",
            rating = (getLong("rating") ?: 0L).toInt(),
            title = getString("title") ?: "",
            body = getString("body") ?: "",
            createdAt = getLong("createdAt") ?: 0L,
            updatedAt = getLong("updatedAt") ?: getLong("createdAt") ?: 0L,
            replyCount = (getLong("replyCount") ?: 0L).toInt(),
            likes = (getLong("likes") ?: 0L).toInt(),
            dislikes = (getLong("dislikes") ?: 0L).toInt(),
            reportedCount = (getLong("reportedCount") ?: 0L).toInt(),
            isDeleted = getBoolean("isDeleted") ?: false
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
            normalizedX = (getDouble("normalizedX") ?: 0.5).toFloat(),
            normalizedY = (getDouble("normalizedY") ?: 0.5).toFloat(),
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
            commentId = getString("commentId"),
            createdAt = getLong("createdAt") ?: 0L,
            read = getBoolean("read") ?: false
        )
    }.getOrNull()

    private fun DocumentSnapshot.toCustomUserList(): CustomUserList? = runCatching {
        CustomUserList(
            id = getString("id") ?: id,
            name = getString("name") ?: return null,
            description = getString("description") ?: "",
            coverUrl = getString("coverUrl") ?: "",
            rating = (getDouble("rating") ?: 0.0).toFloat(),
            genres = (get("genres") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            isPublic = getBoolean("isPublic") ?: false,
            itemCount = (getLong("itemCount") ?: 0L).toInt(),
            createdAt = getLong("createdAt") ?: 0L,
            updatedAt = getLong("updatedAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toCustomUserListItem(): CustomUserListItem? = runCatching {
        CustomUserListItem(
            mangaId = getString("mangaId") ?: return null,
            sourceId = getString("sourceId") ?: return null,
            slug = getString("slug") ?: return null,
            title = getString("title") ?: return null,
            coverUrl = getString("coverUrl") ?: "",
            rating = (getDouble("rating") ?: 0.0).toFloat(),
            genres = (get("genres") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            addedAt = getLong("addedAt") ?: 0L
        )
    }.getOrNull()

    private fun DocumentSnapshot.toModerationReport(): ModerationReport? = runCatching {
        val targetId = getString("targetId") ?: getString("commentId") ?: return null
        ModerationReport(
            id = getString("id") ?: id,
            commentId = getString("commentId") ?: targetId,
            targetId = targetId,
            mangaId = getString("mangaId") ?: return null,
            chapterUrl = getString("chapterUrl"),
            reportedUid = getString("reportedUid") ?: return null,
            reporterUid = getString("reporterUid") ?: return null,
            reason = getString("reason") ?: return null,
            createdAt = getLong("createdAt") ?: 0L,
            status = getString("status") ?: "open",
            targetType = getString("targetType") ?: "comment"
        )
    }.getOrNull()

    private fun Map<String, Any?>.toChatMessage(id: String): CommunityChatMessage? = runCatching {
        CommunityChatMessage(
            id = id,
            roomId = this["roomId"]?.toString() ?: "global",
            authorUid = this["authorUid"]?.toString() ?: return null,
            authorName = this["authorName"]?.toString() ?: "User",
            authorBadge = this["authorBadge"]?.toString() ?: "Beginner",
            text = this["text"]?.toString() ?: return null,
            createdAt = (this["createdAt"] as? Number)?.toLong() ?: 0L
        )
    }.getOrNull()

    // ─── Following ──────────────────────────────────────────────────────
    override suspend fun followUser(targetUid: String) {
        val uid = sessionManager.currentUserId() ?: return
        require(uid != targetUid) { context.getString(R.string.community_error_self_follow) }
        val myProfile = getCurrentProfile()
        val targetProfile = getCurrentProfileForUid(targetUid)
        val followedAt = System.currentTimeMillis()
        val data1 = hashMapOf<String, Any?>(
            "uid" to targetUid, "username" to (targetProfile?.username ?: ""), "followedAt" to followedAt
        )
        val data2 = hashMapOf<String, Any?>(
            "uid" to uid, "username" to (myProfile?.username ?: ""), "followedAt" to followedAt
        )
        firestore.runBatch { batch ->
            batch.set(firestore.collection("relationships").document(uid).collection("following").document(targetUid), data1)
            batch.set(firestore.collection("relationships").document(targetUid).collection("followers").document(uid), data2)
        }.await()
    }

    private suspend fun getCurrentProfileForUid(uid: String): CommunityProfile? =
        firestore.collection("publicProfiles").document(uid).get().await().toProfile()

    private suspend fun publicProfileUsername(uid: String): String =
        getCurrentProfileForUid(uid)?.username ?: ""

    override suspend fun unfollowUser(targetUid: String) {
        val uid = sessionManager.currentUserId() ?: return
        firestore.runBatch { batch ->
            batch.delete(firestore.collection("relationships").document(uid).collection("following").document(targetUid))
            batch.delete(firestore.collection("relationships").document(targetUid).collection("followers").document(uid))
        }.await()
    }

    override fun isFollowing(targetUid: String): Flow<Boolean> = callbackFlow {
        val uid = sessionManager.currentUserId()
        if (uid == null) { trySend(false); close(); return@callbackFlow }
        val reg = firestore.collection("relationships").document(uid).collection("following").document(targetUid)
            .addSnapshotListener { snapshot, _ -> trySend(snapshot?.exists() == true) }
        awaitClose { reg.remove() }
    }

    override fun observeFollowing(userId: String): Flow<List<UserFollow>> = callbackFlow {
        val reg = firestore.collection("relationships").document(userId).collection("following")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    UserFollow(uid = doc.getString("uid") ?: doc.id, username = doc.getString("username") ?: "", followedAt = doc.getLong("followedAt") ?: 0L)
                })
            }
        awaitClose { reg.remove() }
    }

    override fun observeFollowers(userId: String): Flow<List<UserFollow>> = callbackFlow {
        val reg = firestore.collection("relationships").document(userId).collection("followers")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    UserFollow(uid = doc.getString("uid") ?: doc.id, username = doc.getString("username") ?: "", followedAt = doc.getLong("followedAt") ?: 0L)
                })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun getFollowingCount(userId: String): Int = runCatching {
        firestore.collection("relationships").document(userId).collection("following")
            .count().get(AggregateSource.SERVER).await().count
    }.getOrDefault(0L).toInt()

    override suspend fun getFollowersCount(userId: String): Int = runCatching {
        firestore.collection("relationships").document(userId).collection("followers")
            .count().get(AggregateSource.SERVER).await().count
    }.getOrDefault(0L).toInt()

    override suspend fun blockUser(uid: String) {
        settingsRepository.addMutedUser(uid)
    }

    override suspend fun unblockUser(uid: String) {
        settingsRepository.removeMutedUser(uid)
    }

    override fun getBlockedUsers(): Flow<Set<String>> = settingsRepository.getMutedUserIds()
}
