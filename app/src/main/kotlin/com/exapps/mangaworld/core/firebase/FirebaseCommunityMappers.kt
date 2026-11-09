package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.domain.model.CommunityChatMessage
import com.exapps.mangaworld.domain.model.CommunityComment
import com.exapps.mangaworld.domain.model.CommunityNotification
import com.exapps.mangaworld.domain.model.CommunityNotificationType
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.model.CustomUserListItem
import com.exapps.mangaworld.domain.model.MangaReview
import com.exapps.mangaworld.domain.model.ModerationReport
import com.exapps.mangaworld.domain.model.ReaderReaction
import com.google.firebase.firestore.DocumentSnapshot

/**
 * U-35: DocumentSnapshot/DTO mapper extensions extracted from
 * [FirebaseCommunityRepository] (formerly private member extensions; now
 * internal top-level so both delegate axes share them). Same package, so
 * call sites and ReplyCreateContractTest helpers are unaffected.
 */
internal fun CommunityProfile.toEditableMap() = mapOf(
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

internal fun CustomUserList.toMap() = mapOf(
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

internal fun CustomUserListItem.toMap() = mapOf(
    "mangaId" to mangaId,
    "sourceId" to sourceId,
    "slug" to slug,
    "title" to title,
    "coverUrl" to coverUrl,
    "rating" to rating,
    "genres" to genres,
    "addedAt" to addedAt
)

internal fun CommunityComment.toMap() = mapOf(
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

internal fun MangaReview.toMap() = mapOf(
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

internal fun ReaderReaction.toMap() = mapOf(
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

internal fun CommunityNotification.toMap() = mapOf(
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

internal fun CommunityChatMessage.toMap() = mapOf(
    "roomId" to roomId,
    "authorUid" to authorUid,
    "authorName" to authorName,
    "authorBadge" to authorBadge,
    "text" to text,
    "createdAt" to createdAt
)

internal fun DocumentSnapshot.toProfile(): CommunityProfile? = runCatching {
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

internal fun DocumentSnapshot.toComment(): CommunityComment? = runCatching {
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

internal fun DocumentSnapshot.toReview(): MangaReview? = runCatching {
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

internal fun DocumentSnapshot.toReaction(): ReaderReaction? = runCatching {
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

internal fun DocumentSnapshot.toNotification(): CommunityNotification? = runCatching {
    CommunityNotification(
        id = getString("id") ?: id,
        type = getString("type")?.let { raw ->
            runCatching { CommunityNotificationType.valueOf(raw.uppercase()) }.getOrNull()
        } ?: CommunityNotificationType.REPLY,
        title = getString("title") ?: return null,
        body = getString("body") ?: return null,
        mangaId = getString("mangaId") ?: return null,
        slug = getString("slug") ?: "",
        sourceId = getString("sourceId")?.takeIf { id -> com.exapps.mangaworld.core.source.plugins.BuiltinSourceIds.isBuiltin(id) } ?: "azora",
        chapterUrl = getString("chapterUrl"),
        commentId = getString("commentId"),
        targetUid = getString("targetUid"),
        createdAt = getLong("createdAt") ?: 0L,
        read = getBoolean("read") ?: false
    )
}.getOrNull()

internal fun DocumentSnapshot.toCustomUserList(): CustomUserList? = runCatching {
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

internal fun com.exapps.mangaworld.core.data.local.entity.FavoriteEntity.toLibraryDomain(): com.exapps.mangaworld.domain.model.FavoriteManga =
    com.exapps.mangaworld.domain.model.FavoriteManga(
        mangaId = mangaId,
        slug = slug,
        title = title,
        coverUrl = coverUrl,
        source = com.exapps.mangaworld.core.source.plugins.SourceId(sourceId),
        addedAt = addedAt,
        readChapters = readChapters,
        totalChapters = totalChapters,
        readingStatus = readingStatus,
        isFavorite = isFavorite
    )

internal fun DocumentSnapshot.toCustomUserListItem(): CustomUserListItem? = runCatching {
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

internal fun DocumentSnapshot.toModerationReport(): ModerationReport? = runCatching {
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

internal fun Map<String, Any?>.toChatMessage(id: String): CommunityChatMessage? = runCatching {
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
