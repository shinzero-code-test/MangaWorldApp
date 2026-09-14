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
import com.exapps.mangaworld.domain.UsernameRules
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private const val MAX_COMMUNITY_MENTIONS = 10

@Singleton
class FirebaseCommunityRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager,
    private val readChapterDao: ReadChapterDao,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val achievementManager: com.exapps.mangaworld.core.data.AchievementManager,
    private val settingsRepository: com.exapps.mangaworld.domain.repository.SettingsRepository,
    private val telemetry: FirebaseTelemetry
) : CommunityRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val realtimeDb = FirebaseDatabase.getInstance()

    override fun observeMangaComments(mangaId: String): Flow<List<CommunityComment>> =
        observeComments(commentsCollection(mangaId, null))
            .rescue("community-comments", "manga") {
                commentsCollection(mangaId, null)
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .get().await().documents.mapNotNull { it.toComment() }
            }

    override fun observeChapterComments(mangaId: String, chapterUrl: String): Flow<List<CommunityComment>> =
        observeComments(commentsCollection(mangaId, chapterUrl))
            .rescue("community-comments", "chapter") {
                commentsCollection(mangaId, chapterUrl)
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .get().await().documents.mapNotNull { it.toComment() }
            }

    override fun observeReviews(mangaId: String): Flow<List<MangaReview>> = callbackFlow {
        val reg = firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}")
                    // Same no-starvation guarantee as observeComments.
                    reportListenError("community-reviews", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val reviewDocs = snapshot?.documents.orEmpty()
                val mappedReviews = reviewDocs.mapNotNull { it.toReview() }
                if (reviewDocs.size > mappedReviews.size) {
                    runCatching { telemetry.logMapperDrops("community-reviews", reviewDocs.size, mappedReviews.size) }
                }
                trySend(mappedReviews)
            }
        awaitClose { reg.remove() }
    }.rescue("community-reviews", "manga") {
        firestore.collection("community_manga").document(mangaId)
            .collection("reviews")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(200)
            .get().await().documents.mapNotNull { it.toReview() }
    }

    override fun observeReaderPresenceCount(mangaId: String, chapterUrl: String): Flow<Int> = callbackFlow {
        val members = firestore.collection("community_presence").document(threadId(mangaId, chapterUrl))
            .collection("members")
        // Bounded listener: presence is a live-reader *hint*, so counts saturate
        // at 100 rather than downloading an unbounded subcollection per keystroke.
        val reg = members.limit(100).addSnapshotListener { snapshot, error ->
            if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
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
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
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
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
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

            override fun onCancelled(error: DatabaseError) {
                android.util.Log.w("CommunityRepo", "Chat listener cancelled: ${error.code} ${error.message}")
                trySend(emptyList())
            }
        }
        query.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }.rescue("community-chat", "messages") {
        realtimeDb.getReference("chatRooms").child(roomId).child("messages")
            .orderByKey().limitToLast(100)
            .get().await().children.mapNotNull { child ->
                (child.value as? Map<String, Any?>)?.toChatMessage(child.key.orEmpty())
            }.sortedBy { it.createdAt }
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
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserList() })
            }
        awaitClose { reg.remove() }
    }

    override fun observePublicProfile(userId: String): Flow<CommunityProfile?> = callbackFlow {
        val reg = firestore.collection("publicProfiles").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); reportListenError("public-profile", error); trySend(null); return@addSnapshotListener }
                trySend(snapshot?.toProfile())
            }
        awaitClose { reg.remove() }
    }.rescue("public-profile", "doc") {
        firestore.collection("publicProfiles").document(userId).get().await().toProfile()
    }

    override fun observePublicLists(userId: String): Flow<List<CustomUserList>> = callbackFlow {
        val reg = firestore.collection("users").document(userId).collection("lists")
            .whereEqualTo("isPublic", true)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}")
                    reportListenError("public-profile", error)
                    trySend(emptyList())
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        retryQueryNow("public-profile") { fetchPublicLists(userId) }
                    }
                    return@addSnapshotListener
                }
                val listDocs = snapshot?.documents.orEmpty()
                val mappedLists = listDocs.mapNotNull { it.toCustomUserList() }
                if (listDocs.size > mappedLists.size) {
                    runCatching { telemetry.logMapperDrops("public-lists", listDocs.size, mappedLists.size) }
                }
                trySend(mappedLists)
            }
        awaitClose { reg.remove() }
    }.rescue("public-profile", "lists") {
        fetchPublicLists(userId)
    }

    private suspend fun fetchPublicLists(userId: String): List<CustomUserList> =
        firestore.collection("users").document(userId).collection("lists")
            .whereEqualTo("isPublic", true)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get().await().documents.mapNotNull { it.toCustomUserList() }

    override fun observePublicActivity(userId: String): Flow<List<CommunityComment>> = callbackFlow {
        val reg = firestore.collectionGroup("comments")
            .whereEqualTo("authorUid", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}")
                    reportListenError("public-profile", error)
                    trySend(emptyList())
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        retryQueryNow("public-profile") { fetchPublicActivity(userId) }
                    }
                    return@addSnapshotListener
                }
                val actDocs = snapshot?.documents.orEmpty()
                val mappedActivity = actDocs.mapNotNull { it.toComment() }
                if (actDocs.size > mappedActivity.size) {
                    runCatching { telemetry.logMapperDrops("public-activity", actDocs.size, mappedActivity.size) }
                }
                trySend(mappedActivity)
            }
        awaitClose { reg.remove() }
    }.rescue("public-profile", "activity") {
        fetchPublicActivity(userId)
    }

    private suspend fun fetchPublicActivity(userId: String): List<CommunityComment> =
        firestore.collectionGroup("comments")
            .whereEqualTo("authorUid", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get().await().documents.mapNotNull { it.toComment() }

    override fun observePublicLibrary(userId: String): Flow<com.exapps.mangaworld.domain.repository.PublicLibraryState> = callbackFlow {
        // whereIn proves the rules constraint
        // (resource.data.readingStatus in [...]) at query-planning time.
        val statuses = listOf("reading", "completed", "plan_to_read", "on_hold", "dropped")
        val reg = firestore.collection("users").document(userId).collection("favorites")
            .whereIn("readingStatus", statuses)
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Public library listener failed: code=${error.code} message=${error.message}")
                    when (error.code) {
                        // Privacy gate → hidden, not failed; counted, not a non-fatal.
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                            reportListenError("public-library", error)
                            trySend(com.exapps.mangaworld.domain.repository.PublicLibraryState.Ready(emptyList()))
                        }
                        // Query-planning failure (e.g. undeployed index): signal
                        // now AND race the one-shot get() immediately instead of
                        // waiting out the 8s watchdog behind an empty screen.
                        // A transient that recovers still lands via trySend.
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION -> {
                            reportListenError("public-library", error)
                            trySend(com.exapps.mangaworld.domain.repository.PublicLibraryState.Failed)
                            launch {
                                runCatching { fetchPublicLibrary(userId) }
                                    .onSuccess { trySend(com.exapps.mangaworld.domain.repository.PublicLibraryState.Ready(it)) }
                                // Failure here is already reported; Failed stands.
                            }
                        }
                        else -> {
                            reportListenError("public-library", error)
                            trySend(com.exapps.mangaworld.domain.repository.PublicLibraryState.Failed)
                        }
                    }
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                trySend(
                    com.exapps.mangaworld.domain.repository.PublicLibraryState.Ready(
                        docs.mapNotNull { FirebaseSyncMerge.favorite(it)?.toLibraryDomain() }
                    )
                )
            }
        awaitClose { reg.remove() }
    }.rescue("public-library", "favorites") {
        // Never rethrow: the listener already reported; map every outcome so
        // the watchdog's onFallbackError stays silent for this surface.
        runCatching { fetchPublicLibrary(userId) }.fold(
            onSuccess = { com.exapps.mangaworld.domain.repository.PublicLibraryState.Ready(it) },
            onFailure = { e ->
                if ((e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                ) {
                    com.exapps.mangaworld.domain.repository.PublicLibraryState.Ready(emptyList())
                } else {
                    com.exapps.mangaworld.domain.repository.PublicLibraryState.Failed
                }
            }
        )
    }

    private suspend fun fetchPublicLibrary(userId: String): List<com.exapps.mangaworld.domain.model.FavoriteManga> {
        val statuses = listOf("reading", "completed", "plan_to_read", "on_hold", "dropped")
        return firestore.collection("users").document(userId).collection("favorites")
            .whereIn("readingStatus", statuses)
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .limit(200).get().await()
            .documents.mapNotNull { FirebaseSyncMerge.favorite(it)?.toLibraryDomain() }
    }

    override fun observeModerationReports(): Flow<List<ModerationReport>> = callbackFlow {
        val reg = firestore.collection("moderationReports")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
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
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); return@addSnapshotListener }
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
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}")
                    reportListenError("public-profile", error)
                    trySend(emptyList())
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        retryQueryNow("public-profile") { fetchPublicListItems(userId, listId) }
                    }
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toCustomUserListItem() })
            }
        awaitClose { reg.remove() }
    }

    private suspend fun fetchPublicListItems(userId: String, listId: String): List<CustomUserListItem> =
        firestore.collection("users").document(userId)
            .collection("lists").document(listId)
            .collection("items")
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .limit(200)
            .get().await().documents.mapNotNull { it.toCustomUserListItem() }

    override suspend fun getCurrentProfile(): CommunityProfile? {
        val uid = sessionManager.ensureFirebaseSession() ?: return null
        val existing = firestore.collection("publicProfiles").document(uid).get().await().toProfile()
        return existing ?: defaultProfile(uid)
    }

    override suspend fun getUidForUsername(username: String): String? = runCatching {
        val normalized = username.trim().trimStart('@').lowercase()
        if (normalized.isBlank()) return null
        firestore.collection("usernames").document(normalized).get().await().getString("uid")
    }.getOrNull()

    override suspend fun upsertProfile(username: String, bio: String, isPublic: Boolean, avatarUrl: String?, bannerUrl: String?, displayName: String, location: String, birthday: Long?) {
        withContext(NonCancellable) {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withContext
        val normalized = username.trim().lowercase()
        require(normalized.isNotBlank()) { context.getString(R.string.auth_error_username_required) }
        require(normalized.length in 3..20) { context.getString(R.string.auth_error_username_invalid) }
        require(UsernameRules.REGEX.matches(normalized)) { context.getString(R.string.auth_error_username_invalid) }

        val existing = getCurrentProfile() ?: defaultProfile(uid)
        // Recalculate badge based on current achievements
        val newBadge = try { achievementManager.calculateBadge() } catch (_: Exception) { existing.badgeLabel }
        // A-13: displayName/location are pass-through (blank clears them) —
        // the old ifBlank-keep-old fallback made them impossible to clear.
        // A-14: backfill createdAt for pre-v8.4.5 profiles that lack it.
        val now = System.currentTimeMillis()
        val profile = existing.copy(
            username = username.trim(),
            displayName = displayName.trim(),
            bio = bio.trim(),
            location = location.trim(),
            birthday = birthday,
            isPublic = isPublic,
            badgeLabel = newBadge,
            avatarUrl = avatarUrl ?: existing.avatarUrl,
            bannerUrl = bannerUrl ?: existing.bannerUrl,
            createdAt = existing.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = now
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
    }

    override suspend fun updateProfilePrivacy(showListsPublic: Boolean, showActivityPublic: Boolean, showLibraryPublic: Boolean) {
        withContext(NonCancellable) {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withContext
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
    }

    override suspend fun createOrUpdateList(listId: String?, name: String, description: String, coverUrl: String, rating: Float, genres: List<String>, isPublic: Boolean): String {
        return withContext(NonCancellable) {
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
        id
        }
    }

    override suspend fun deleteList(listId: String) {
        withContext(NonCancellable) {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withContext
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
    }

    override suspend fun addMangaToList(listId: String, item: CustomUserListItem) {
        withContext(NonCancellable) {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withContext
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
    }

    override suspend fun removeMangaFromList(listId: String, mangaId: String) {
        withContext(NonCancellable) {
        val uid = sessionManager.ensureFirebaseSession() ?: return@withContext
        val listDoc = firestore.collection("users").document(uid).collection("lists").document(listId)
        val itemDoc = listDoc.collection("items").document(mangaId)
        // Mirror addMangaToList: only decrement when the item actually existed,
        // otherwise repeated removes drive itemCount negative (L-review).
        val existed = itemDoc.get().await().exists()
        if (!existed) return@withContext
        itemDoc.delete().await()
        listDoc.update(
            mapOf(
                "itemCount" to FieldValue.increment(-1),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
        }
    }

    override suspend fun postMangaComment(mangaId: String, slug: String, sourceId: String, text: String, spoiler: Boolean, replyTarget: com.exapps.mangaworld.domain.model.CommunityReplyTarget?) {
        postComment(mangaId, slug, sourceId, null, text, spoiler, replyTarget)
    }

    override suspend fun postChapterComment(mangaId: String, slug: String, sourceId: String, chapterUrl: String, text: String, spoiler: Boolean, replyTarget: com.exapps.mangaworld.domain.model.CommunityReplyTarget?) {
        postComment(mangaId, slug, sourceId, chapterUrl, text, spoiler, replyTarget)
    }

    override suspend fun upsertReview(mangaId: String, slug: String, sourceId: String, rating: Int, title: String, body: String) {
        // Review edits must survive navigation: the ViewModel's viewModelScope is
        // cancelled the moment the screen is left, which previously aborted the
        // read-then-write below mid-flight while the UI already showed success.
        // NonCancellable lets this block finish even after the parent scope dies.
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
        // Edit-mentions: the dashboard diffs @mentions in title+body against
        // already-notified ones and notifies only fresh mentions (best-effort).
        notifyEdit(mangaId = mangaId, chapterUrl = null, commentId = null, reviewId = profile.uid)
        }
    }

    override suspend fun updateComment(comment: CommunityComment, text: String, spoiler: Boolean) {
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
        // Edit-mentions: server diffs against already-notified mentions so only
        // newly-added @mentions fire (best-effort, never fails the edit).
        notifyEdit(mangaId = comment.mangaId, chapterUrl = comment.chapterUrl, commentId = comment.id)
        }
    }

    override suspend fun deleteComment(comment: CommunityComment) {
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
            // leaving a "[deleted]" husk behind. If the server still considers
            // it referenced (fresh replies landed since this snapshot), rules
            // deny the delete — degrade to the soft anchor so the user's
            // intent always succeeds visibly.
            try {
                docRef.delete().await()
            } catch (_: Exception) {
                softDeleteComment(docRef)
            }
        }
        }
    }

    private suspend fun softDeleteComment(docRef: com.google.firebase.firestore.DocumentReference) {
        docRef.update(
            mapOf(
                "text" to "",
                "mentions" to emptyList<String>(),
                "isDeleted" to true,
                "editedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun deleteReview(review: MangaReview) {
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
            // the one-review-per-user slot for a fresh write (v8 #8). Same
            // PERMISSION_DENIED fallback as comments: a reply may have landed
            // after this snapshot was taken.
            try {
                reviewRef.delete().await()
            } catch (_: Exception) {
                reviewRef.update(
                    mapOf(
                        "title" to "",
                        "body" to "",
                        "isDeleted" to true,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
        }
        }
    }

    override suspend fun sendPageReaction(mangaId: String, chapterUrl: String, pageIndex: Int, emoji: String, normalizedX: Float, normalizedY: Float) {
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
        require(emoji.trim().length <= 16) { context.getString(R.string.community_error_empty_content) }
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
    }

    override suspend fun sendChatMessage(roomId: String, text: String) {
        withContext(NonCancellable) {
        // Chat is named-users-only. Checking BEFORE currentProfileOrThrow() matters:
        // that helper silently provisions an anonymous session, which the RTDB rules
        // now reject — guests must get a sign-in error, not a provisioned identity.
        val currentUser = sessionManager.currentUser()
        if (currentUser == null || currentUser.isAnonymous) {
            error(context.getString(R.string.community_error_sign_in))
        }
        val profile = requireNamedProfile()
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

    override suspend fun likeComment(commentId: String): com.exapps.mangaworld.domain.model.VoteOutcome {
        return voteOnContent(targetType = "comment", mangaId = null, targetId = commentId, vote = 1)
    }

    override suspend fun dislikeComment(commentId: String): com.exapps.mangaworld.domain.model.VoteOutcome {
        return voteOnContent(targetType = "comment", mangaId = null, targetId = commentId, vote = -1)
    }

    override suspend fun likeReview(mangaId: String, reviewId: String): com.exapps.mangaworld.domain.model.VoteOutcome {
        return voteOnContent(targetType = "review", mangaId = mangaId, targetId = reviewId, vote = 1)
    }

    override suspend fun dislikeReview(mangaId: String, reviewId: String): com.exapps.mangaworld.domain.model.VoteOutcome {
        return voteOnContent(targetType = "review", mangaId = mangaId, targetId = reviewId, vote = -1)
    }

    override suspend fun fetchCommentVote(targetId: String): com.exapps.mangaworld.domain.model.VoteOutcome? = runCatching {
        if (targetId.isBlank()) return null
        val snap = firestore.collectionGroup("comments")
            .whereEqualTo("id", targetId)
            .limit(1).get().await()
        val doc = snap.documents.firstOrNull() ?: return null
        com.exapps.mangaworld.domain.model.VoteOutcome(
            likes = (doc.getLong("likes") ?: 0L).toInt(),
            dislikes = (doc.getLong("dislikes") ?: 0L).toInt()
        )
    }.getOrNull()

    override suspend fun fetchReviewVote(mangaId: String, reviewId: String): com.exapps.mangaworld.domain.model.VoteOutcome? = runCatching {
        if (mangaId.isBlank() || reviewId.isBlank()) return null
        val doc = firestore.collection("community_manga").document(mangaId)
            .collection("reviews").document(reviewId).get().await()
        if (!doc.exists()) return null
        com.exapps.mangaworld.domain.model.VoteOutcome(
            likes = (doc.getLong("likes") ?: 0L).toInt(),
            dislikes = (doc.getLong("dislikes") ?: 0L).toInt()
        )
    }.getOrNull()

    override suspend fun setReaderPresence(mangaId: String, chapterUrl: String, active: Boolean) {
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
    }

    override suspend fun markNotificationRead(notificationId: String) {
        withContext(NonCancellable) {
        val uid = sessionManager.currentUserId() ?: return@withContext
        firestore.collection("users").document(uid)
            .collection("notifications").document(notificationId)
            .update("read", true)
            .await()
        }
    }

    override suspend fun markNotificationsRead(ids: List<String>) {
        withContext(NonCancellable) {
        val uid = sessionManager.currentUserId() ?: return@withContext
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
    }

    /**
     * Listener watchdog: races the live snapshot stream against a one-shot
     * `get()` so a stalled listener can never pin screens on blank initial
     * state (reads land via unary RPC even when the Watch stream stalls —
     * exactly the "saved in Firestore but invisible in the app" report).
     * Rescue-fetch failures are reported unless plainly offline.
     */
    private fun <T> Flow<T>.rescue(
        surface: String,
        detail: String,
        fallback: suspend () -> T
    ): Flow<T> = withStarvationFallback(LISTENER_STARVATION_TIMEOUT_MS, fallback) { e ->
        val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
        // UNAVAILABLE = offline (routine). PERMISSION_DENIED = privacy gate
        // (private profile / hidden section / signed-out viewer) — expected.
        if (code != com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
            && code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        ) {
            runCatching { telemetry.logListenerStarvation(surface, "$detail:rescue", e) }
        }
    }

    /**
     * Reports a failed snapshot listener to Crashlytics — offline stalls and
     * privacy denials are routine and skipped. Anything else
     * (failed-precondition, unavailable-index…) is a config bug worth a
     * non-fatal with the surface that hit it.
     */
    private fun reportListenError(surface: String, error: com.google.firebase.firestore.FirebaseFirestoreException) {
        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE) return
        // Privacy denials stay non-fatals-free, but are now COUNTED per surface
        // (logListenerDenial) so "denied" stops being indistinguishable from
        // "empty" and "failed" in diagnostics.
        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            runCatching { telemetry.logListenerDenial(surface, error.code.name) }
            return
        }
        runCatching { telemetry.logListenerStarvation(surface, "listen", error) }
    }

    /**
     * Query-planning failures (FAILED_PRECONDITION — e.g. an index that hasn't
     * finished deploying) get both an immediate report AND an immediate
     * one-shot retry, instead of waiting out the 8s starvation watchdog
     * behind an empty screen. Callers emit empty first for UI liveness; a
     * recovered fetch still lands via trySend. A retry that fails the SAME way
     * stays silent (the listener already reported this incident).
     */
    private fun <T> kotlinx.coroutines.channels.ProducerScope<T>.retryQueryNow(
        surface: String,
        fetch: suspend () -> T
    ) = launch {
        runCatching { fetch() }
            .onSuccess { trySend(it) }
            .onFailure { e ->
                val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                if (code != null
                    && code != com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION
                    && code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                    && code != com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
                ) {
                    runCatching { telemetry.logListenerStarvation(surface, "immediate-retry", e) }
                }
            }
    }

    private fun observeComments(collection: com.google.firebase.firestore.CollectionReference): Flow<List<CommunityComment>> = callbackFlow {
        val reg = collection.orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}")
                    // Never starve collectors: an errored listener that stays
                    // silent freezes every combine() downstream (blank screen,
                    // dead tabs). Emit empty and keep listening for recovery.
                    reportListenError("community-comments", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                val mapped = docs.mapNotNull { it.toComment() }
                if (docs.size > mapped.size) {
                    runCatching { telemetry.logMapperDrops("community-comments", docs.size, mapped.size) }
                }
                trySend(mapped)
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
        withContext(NonCancellable) {
        val profile = requireNamedProfile()
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
                val conn = java.net.URL("${CloudinaryUploader.DASHBOARD_BASE_URL}/api/notifications/push-reply").openConnection() as java.net.HttpURLConnection
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

    /**
     * Edit-mention trigger: the dashboard diffs current @mentions against
     * already-notified ones in `commentNotificationDispatches` and notifies
     * only fresh mentions. Best-effort — never fails the edit.
     */
    private suspend fun notifyEdit(
        mangaId: String,
        chapterUrl: String?,
        commentId: String? = null,
        reviewId: String? = null
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val token = sessionManager.currentIdToken() ?: return@withContext
            val body = org.json.JSONObject().apply {
                put("mangaId", mangaId)
                if (chapterUrl != null) put("chapterUrl", chapterUrl)
                if (commentId != null) put("commentId", commentId)
                if (reviewId != null) put("reviewId", reviewId)
            }
            val conn = java.net.URL("${CloudinaryUploader.DASHBOARD_BASE_URL}/api/notifications/push-edit").openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Unit
        }
    }

    /**
     * Follow trigger: client already wrote the relationships batch; the server
     * verifies it exists then fans out the FOLLOW notification + FCM via the
     * Admin SDK (clients cannot write to another user's notifications).
     */
    private suspend fun notifyFollow(targetUid: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = sessionManager.currentIdToken() ?: return@withContext
                val body = org.json.JSONObject().apply { put("targetUid", targetUid) }
                val conn = java.net.URL("${CloudinaryUploader.DASHBOARD_BASE_URL}/api/notifications/follow").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Unit
            }
        }

    private suspend fun voteOnContent(targetType: String, mangaId: String?, targetId: String, vote: Int): com.exapps.mangaworld.domain.model.VoteOutcome {
        require(targetId.isNotBlank()) { context.getString(R.string.community_error_invalid_content) }
        // Votes must survive navigation like every other community write.
        return withContext(NonCancellable) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val token = sessionManager.currentIdToken() ?: error(context.getString(R.string.community_error_sign_in))
            val body = org.json.JSONObject().apply {
                put("targetType", targetType)
                put("targetId", targetId)
                if (mangaId != null) put("mangaId", mangaId)
                put("vote", vote)
            }
            val conn = java.net.URL("${CloudinaryUploader.DASHBOARD_BASE_URL}/api/community/vote").openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
                check(conn.responseCode in 200..299) { context.getString(R.string.community_error_vote) }
                // Authoritative post-vote state (rec-2): drives the optimistic
                // echo reconcile. Strict read — a body without counts is a
                // failed vote like any other non-2xx.
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val action = json.optString("action", "added")
                com.exapps.mangaworld.domain.model.VoteOutcome(
                    likes = json.getInt("likes"),
                    dislikes = json.getInt("dislikes"),
                    myVote = if (action == "removed") null else vote,
                    action = action
                )
            } finally {
                conn.disconnect()
            }
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
        withContext(NonCancellable) {
        val reporter = requireNamedProfile()
        val trimmedReason = reason.trim().take(500)
        require(trimmedReason.isNotBlank()) { context.getString(R.string.community_error_report_reason_required) }
        // #2 report-flood quota + dedupe. Firestore rules cannot rate-limit, so
        // the client enforces: at most MAX_REPORTS_PER_HOUR recent reports per
        // reporter (single-field query — no composite index needed), and one
        // open report per reporter+target via a deterministic doc ID, so a
        // double-tap or replay cannot stack duplicates.
        val hourAgo = System.currentTimeMillis() - REPORT_WINDOW_MS
        val recent = firestore.collection("moderationReports")
            .whereEqualTo("reporterUid", reporter.uid)
            .limit(REPORT_QUOTA_PROBE)
            .get().await()
        val recentCount = recent.documents.count {
            (it.getLong("createdAt") ?: 0L) > hourAgo
        }
        require(recentCount < MAX_REPORTS_PER_HOUR) { context.getString(R.string.community_error_report_quota) }
        val dedupeId = reportDedupeId(reporter.uid, targetType, targetId)
        val existing = firestore.collection("moderationReports").document(dedupeId).get().await()
        if (existing.exists() && existing.getString("status") == "open") {
            error(context.getString(R.string.community_error_report_duplicate))
        }
        val reportId = if (existing.exists()) UUID.randomUUID().toString() else dedupeId
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
    }

    private suspend fun currentProfileOrThrow(): CommunityProfile {
        val uid = sessionManager.ensureFirebaseSession() ?: error(context.getString(R.string.community_error_sign_in))
        return getCurrentProfile() ?: defaultProfile(uid)
    }

    /**
     * Write-path gate: community writes are named-users-only. This must run
     * BEFORE [currentProfileOrThrow], which silently provisions an anonymous
     * session that server rules reject — guests get a sign-in error, not an
     * opaque PERMISSION_DENIED. (F8: defense in depth alongside
     * firestore.rules canWriteCommunity.)
     */
    private suspend fun requireNamedProfile(): CommunityProfile {
        val currentUser = sessionManager.currentUser()
        if (currentUser == null || currentUser.isAnonymous) {
            error(context.getString(R.string.community_error_sign_in))
        }
        return currentProfileOrThrow()
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
        val now = System.currentTimeMillis()
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
            bio = "",
            createdAt = now
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
                val conn = java.net.URL("${CloudinaryUploader.DASHBOARD_BASE_URL}/api/community/moderate").openConnection() as java.net.HttpURLConnection
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
        "location" to location,
        "birthday" to birthday,
        "createdAt" to createdAt,
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
        "targetUid" to targetUid,
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
            // Never null the whole profile over a missing username (legacy /
            // dashboard-provisioned docs): the screen falls back to
            // displayName / generic label and still shows lists + activity.
            username = getString("username") ?: "",
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
            location = getString("location") ?: "",
            birthday = getLong("birthday"),
            createdAt = getLong("createdAt") ?: 0L,
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
            type = getString("type")?.let { raw ->
                runCatching { CommunityNotificationType.valueOf(raw.uppercase()) }.getOrNull()
            } ?: CommunityNotificationType.REPLY,
            title = getString("title") ?: return null,
            body = getString("body") ?: return null,
            mangaId = getString("mangaId") ?: return null,
            slug = getString("slug") ?: "",
            sourceId = getString("sourceId")?.takeIf { id -> com.exapps.mangaworld.domain.model.MangaSource.entries.any { it.id == id } } ?: "azora",
            chapterUrl = getString("chapterUrl"),
            commentId = getString("commentId"),
            targetUid = getString("targetUid"),
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

    private fun com.exapps.mangaworld.core.data.local.entity.FavoriteEntity.toLibraryDomain(): com.exapps.mangaworld.domain.model.FavoriteManga =
        com.exapps.mangaworld.domain.model.FavoriteManga(
            mangaId = mangaId,
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            source = com.exapps.mangaworld.domain.model.MangaSource.fromId(sourceId),
            addedAt = addedAt,
            readChapters = readChapters,
            totalChapters = totalChapters,
            readingStatus = readingStatus,
            isFavorite = isFavorite
        )

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
        // FOLLOW notification is fanned out server-side (Admin SDK bypasses the
        // owner-only users/{uid}/notifications rule). Best-effort.
        notifyFollow(targetUid)
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
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); reportListenError("relationships", error); trySend(emptyList()); return@addSnapshotListener }
                trySend(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    UserFollow(uid = doc.getString("uid") ?: doc.id, username = doc.getString("username") ?: "", followedAt = doc.getLong("followedAt") ?: 0L)
                })
            }
        awaitClose { reg.remove() }
    }

    override fun observeFollowers(userId: String): Flow<List<UserFollow>> = callbackFlow {
        val reg = firestore.collection("relationships").document(userId).collection("followers")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { android.util.Log.w("CommunityRepo", "Snapshot listener failed: code=${error.code} message=${error.message}"); reportListenError("relationships", error); trySend(emptyList()); return@addSnapshotListener }
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

    override suspend fun getPublicUsernames(uids: Set<String>): Map<String, String> {
        if (uids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        // Small set (blocked users are few); per-doc reads with per-doc
        // guards so one private/denied profile can't fail the whole lookup.
        for (uid in uids.take(MAX_USERNAME_LOOKUP)) {
            val name = runCatching {
                val doc = firestore.collection("publicProfiles").document(uid).get().await()
                doc.getString("displayName")?.takeIf { it.isNotBlank() }
                    ?: doc.getString("username")?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (!name.isNullOrBlank()) result[uid] = name
        }
        return result
    }

    private companion object {
        const val MAX_USERNAME_LOOKUP = 100
    }
}

/** At most this many reports per reporter per rolling hour (#2 flood quota). */
internal const val MAX_REPORTS_PER_HOUR = 10
internal const val REPORT_WINDOW_MS = 3_600_000L

/** Probe cap: quota math only needs to know "more than 10 in the window". */
private const val REPORT_QUOTA_PROBE = 25L

/** Deterministic open-report doc ID: reporter x type x target (#2 dedupe). */
internal fun reportDedupeId(reporterUid: String, targetType: String, targetId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val raw = "$reporterUid|$targetType|$targetId".toByteArray()
    return "r_" + digest.digest(raw).joinToString("") { "%02x".format(it) }
}
