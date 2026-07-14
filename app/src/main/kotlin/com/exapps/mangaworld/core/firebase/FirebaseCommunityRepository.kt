package com.exapps.mangaworld.core.firebase

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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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

@Singleton
class FirebaseCommunityRepository @Inject constructor(
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
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toReview() })
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
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = snapshot.children.mapNotNull { child ->
                    (child.value as? Map<String, Any?>)?.toChatMessage(child.key.orEmpty())
                }.sortedBy { it.createdAt }.takeLast(100)
                trySend(messages)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addValueEventListener(listener)
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
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserListItem() })
            }
        awaitClose { reg.remove() }
    }

    override suspend fun getCurrentProfile(): CommunityProfile? {
        val uid = sessionManager.ensureGuestSession() ?: return null
        val existing = firestore.collection("publicProfiles").document(uid).get().await().toProfile()
        return existing ?: defaultProfile(uid)
    }

    override suspend fun upsertProfile(username: String, bio: String, isPublic: Boolean, avatarUrl: String?, bannerUrl: String?) {
        val uid = sessionManager.ensureGuestSession() ?: return
        val normalized = username.trim().lowercase()
        require(normalized.isNotBlank()) { "اسم المستخدم مطلوب" }

        val existing = getCurrentProfile() ?: defaultProfile(uid)
        // Recalculate badge based on current achievements
        val newBadge = try { achievementManager.calculateBadge() } catch (_: Exception) { existing.badgeLabel }
        val profile = existing.copy(
            username = username.trim(),
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
            val currentOwner = transaction.get(usernameRef).getString("uid")
            require(currentOwner == null || currentOwner == uid) { "اسم المستخدم مستخدم بالفعل" }
            transaction.set(profileRef, profile.toEditableMap(), SetOptions.merge())
            transaction.set(
                usernameRef,
                mapOf("uid" to uid, "username" to profile.username, "updatedAt" to profile.updatedAt)
            )
        }.await()
    }

    override suspend fun updateProfilePrivacy(showListsPublic: Boolean, showActivityPublic: Boolean, showLibraryPublic: Boolean) {
        val uid = sessionManager.ensureGuestSession() ?: return
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
        val uid = sessionManager.ensureGuestSession() ?: error("No user")
        val id = listId ?: UUID.randomUUID().toString()
        val doc = firestore.collection("users").document(uid).collection("lists").document(id)
        val existingCount = runCatching { doc.collection("items").get().await().size() }.getOrDefault(0)
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
        val uid = sessionManager.ensureGuestSession() ?: return
        val doc = firestore.collection("users").document(uid).collection("lists").document(listId)
        val items = doc.collection("items").get().await().documents
        firestore.runBatch { batch ->
            items.forEach { batch.delete(it.reference) }
            batch.delete(doc)
        }.await()
    }

    override suspend fun addMangaToList(listId: String, item: CustomUserListItem) {
        val uid = sessionManager.ensureGuestSession() ?: return
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
        val uid = sessionManager.ensureGuestSession() ?: return
        val listDoc = firestore.collection("users").document(uid).collection("lists").document(listId)
        listDoc.collection("items").document(mangaId).delete().await()
        listDoc.update(
            mapOf(
                "itemCount" to FieldValue.increment(-1),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun postMangaComment(mangaId: String, slug: String, sourceId: String, text: String, spoiler: Boolean, parentId: String?) {
        postComment(mangaId, slug, sourceId, null, text, spoiler, parentId)
    }

    override suspend fun postChapterComment(mangaId: String, slug: String, sourceId: String, chapterUrl: String, text: String, spoiler: Boolean, parentId: String?) {
        postComment(mangaId, slug, sourceId, chapterUrl, text, spoiler, parentId)
    }

    override suspend fun upsertReview(mangaId: String, slug: String, sourceId: String, rating: Int, title: String, body: String) {
        val profile = currentProfileOrThrow()
        validateModeration(title)
        validateModeration(body)
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

    override suspend fun sendPageReaction(mangaId: String, chapterUrl: String, pageIndex: Int, emoji: String, normalizedX: Float, normalizedY: Float) {
        val profile = currentProfileOrThrow()
        val reaction = ReaderReaction(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            emoji = emoji,
            authorUid = profile.uid,
            authorName = profile.username,
            normalizedX = normalizedX.coerceIn(0f, 1f),
            normalizedY = normalizedY.coerceIn(0f, 1f)
        )
        firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("reactions").document(reaction.id)
            .set(reaction.toMap())
            .await()
    }

    override suspend fun sendChatMessage(roomId: String, text: String) {
        val profile = currentProfileOrThrow()
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "الرسالة فارغة" }
        validateModeration(trimmed)
        val ref = realtimeDb.getReference("chatRooms").child(roomId).child("messages").push()
        val message = CommunityChatMessage(
            id = ref.key ?: UUID.randomUUID().toString(),
            roomId = roomId,
            authorUid = profile.uid,
            authorName = profile.username,
            authorBadge = profile.badgeLabel,
            text = trimmed,
            createdAt = System.currentTimeMillis()
        )
        ref.setValue(message.toMap()).await()
    }

    override suspend fun reportComment(comment: CommunityComment, reason: String) {
        val reporter = currentProfileOrThrow()
        val reportId = UUID.randomUUID().toString()
        firestore.collection("moderationReports").document(reportId)
            .set(
                mapOf(
                    "id" to reportId,
                    "commentId" to comment.id,
                    "mangaId" to comment.mangaId,
                    "chapterUrl" to comment.chapterUrl,
                    "reportedUid" to comment.authorUid,
                    "reporterUid" to reporter.uid,
                    "reason" to reason.trim(),
                    "createdAt" to System.currentTimeMillis(),
                    "status" to "open"
                )
            ).await()
    }

    override suspend fun resolveModerationReport(reportId: String, status: String) {
        firestore.collection("moderationReports").document(reportId)
            .update("status", status)
            .await()
    }

    override suspend fun setReaderPresence(mangaId: String, chapterUrl: String, active: Boolean) {
        val profile = currentProfileOrThrow()
        val doc = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members").document(profile.uid)
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
        parentId: String?
    ) {
        val profile = currentProfileOrThrow()
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "التعليق فارغ" }
        validateModeration(trimmed)
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
        collection.document(comment.id).set(
            comment.toMap() + mapOf("slug" to slug, "sourceId" to sourceId)
        ).await()
        if (parentId != null || comment.mentions.isNotEmpty()) {
            sendPushNotification(mangaId, chapterUrl, comment.id)
        }
    }

    private suspend fun sendPushNotification(mangaId: String, chapterUrl: String?, commentId: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val token = sessionManager.currentIdToken() ?: return@runCatching
                val baseUrl = "https://mangaworld-admin.vercel.app"
                val body = org.json.JSONObject().apply {
                    put("mangaId", mangaId)
                    put("commentId", commentId)
                    if (chapterUrl != null) put("chapterUrl", chapterUrl)
                }
                val conn = java.net.URL("$baseUrl/api/notifications/push-reply").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
                conn.responseCode // just trigger the request
                conn.disconnect()
            }
        }
    }

    private suspend fun currentProfileOrThrow(): CommunityProfile {
        val uid = sessionManager.ensureGuestSession() ?: error("No user")
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
                else -> "Beginner"
            }
        }
        return CommunityProfile(
            uid = uid,
            username = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: "reader_${uid.takeLast(6)}",
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

    private fun stableChapterKey(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun extractMentions(text: String): List<String> = Regex("@([A-Za-z0-9_]{3,30})")
        .findAll(text)
        .map { it.groupValues[1] }
        .toList()

    private fun validateModeration(text: String) {
        val banned = remoteConfigManager.bannedKeywords.value
        require(!containsBannedKeyword(text, banned)) {
            "المحتوى يحتوي على كلمات محظورة"
        }
    }

    private fun CommunityProfile.toEditableMap() = mapOf(
        "username" to username,
        "avatarUrl" to avatarUrl,
        "bannerUrl" to bannerUrl,
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
        "parentId" to parentId,
        "authorUid" to authorUid,
        "authorName" to authorName,
        "authorAvatarUrl" to authorAvatarUrl,
        "authorBadge" to authorBadge,
        "text" to text,
        "mentions" to mentions,
        "spoiler" to spoiler,
        "reportedCount" to reportedCount,
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
            avatarUrl = getString("avatarUrl"),
            bannerUrl = getString("bannerUrl"),
            badgeLabel = getString("badgeLabel") ?: "Beginner",
            role = "viewer",
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
            parentId = getString("parentId"),
            authorUid = getString("authorUid") ?: return null,
            authorName = getString("authorName") ?: "User",
            authorAvatarUrl = getString("authorAvatarUrl"),
            authorBadge = getString("authorBadge") ?: "Beginner",
            text = getString("text") ?: return null,
            mentions = (get("mentions") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
            spoiler = getBoolean("spoiler") ?: false,
            reportedCount = (getLong("reportedCount") ?: 0L).toInt(),
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
        ModerationReport(
            id = getString("id") ?: id,
            commentId = getString("commentId") ?: return null,
            mangaId = getString("mangaId") ?: return null,
            chapterUrl = getString("chapterUrl"),
            reportedUid = getString("reportedUid") ?: return null,
            reporterUid = getString("reporterUid") ?: return null,
            reason = getString("reason") ?: return null,
            createdAt = getLong("createdAt") ?: 0L,
            status = getString("status") ?: "open"
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
        val myProfile = getCurrentProfile()
        val data1 = hashMapOf<String, Any?>(
            "uid" to targetUid, "username" to "", "followedAt" to System.currentTimeMillis()
        )
        val data2 = hashMapOf<String, Any?>(
            "uid" to uid, "username" to (myProfile?.username ?: ""), "followedAt" to System.currentTimeMillis()
        )
        firestore.runBatch { batch ->
            batch.set(firestore.collection("relationships").document(uid).collection("following").document(targetUid), data1)
            batch.set(firestore.collection("relationships").document(targetUid).collection("followers").document(uid), data2)
        }.await()
    }

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
        firestore.collection("relationships").document(userId).collection("following").get().await().size()
    }.getOrDefault(0)

    override suspend fun getFollowersCount(userId: String): Int = runCatching {
        firestore.collection("relationships").document(userId).collection("followers").get().await().size()
    }.getOrDefault(0)

    override suspend fun blockUser(uid: String) {
        val current = settingsRepository.getAppSettings().first().mutedUserIds
        settingsRepository.setMutedUserIds(current + uid)
    }

    override suspend fun unblockUser(uid: String) {
        val current = settingsRepository.getAppSettings().first().mutedUserIds
        settingsRepository.setMutedUserIds(current - uid)
    }

    override fun getBlockedUsers(): Flow<Set<String>> = settingsRepository.getMutedUserIds()
}
