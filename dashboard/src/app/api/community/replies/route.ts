import { createHash, randomUUID } from "crypto";
import { FieldValue, type DocumentReference, type Firestore } from "firebase-admin/firestore";
import { NextRequest, NextResponse } from "next/server";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";
import { isTextAllowed } from "@/lib/moderation";

export const dynamic = "force-dynamic";

// U-19: reply-create + root replyCount increment in ONE Admin-SDK transaction.
// The old tally path (client write + rate-limited push-reply side-channel)
// could fail the bump while the reply landed, letting replyCount sit at 0
// and allowing a root hard-delete that orphans the thread. This endpoint is
// the replacement: the reply doc and the bump commit or fail together.
//
// Bound: 2000 mirrors firestore.rules validCommentBase + moderation
// MAX_TEXT_LENGTH. Chat is 500; comments/replies are 2000 — a 500 cap here
// would reject valid existing replies.
const MAX_TEXT_LENGTH = 2_000;
const MAX_SLUG_LENGTH = 2_048;
const MAX_SOURCE_LENGTH = 128;
const MAX_AUTHOR_NAME_LENGTH = 128;
const MAX_USERNAME_LENGTH = 64;
const MAX_URL_LENGTH = 2_048;
const MAX_BADGE_LENGTH = 64;

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
    if (!(await allowAppMutation(`community-replies:${user.uid}`, 30, 60 * 1000))) {
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }

    const payload = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    if (!payload) {
      return NextResponse.json({ error: "طلب رد غير صالح" }, { status: 400 });
    }

    const mangaId = payload.mangaId;
    const text = typeof payload.text === "string" ? payload.text.trim() : "";
    const chapterUrl = normalizeChapterUrl(payload.chapterUrl);
    const slug = normalizeBoundedString(payload.slug, MAX_SLUG_LENGTH);
    const sourceId = normalizeBoundedString(payload.sourceId, MAX_SOURCE_LENGTH);
    const parentId = normalizeTargetId(payload.parentId);
    const reviewId = normalizeTargetId(payload.reviewId);
    const spoiler = payload.spoiler === true;

    if (
      !isIdentifier(mangaId) ||
      chapterUrl === "invalid" ||
      slug === "invalid" ||
      sourceId === "invalid" ||
      parentId === "invalid" ||
      reviewId === "invalid" ||
      !text ||
      text.length > MAX_TEXT_LENGTH ||
      (parentId === null) === (reviewId === null)
    ) {
      return NextResponse.json({ error: "طلب رد غير صالح" }, { status: 400 });
    }

    // Fail-closed content gate (isTextAllowed itself is fail-open on scan
    // outages). A banned reply must never commit printably.
    if (!(await isTextAllowed(text))) {
      return NextResponse.json({ error: "يحتوي المحتوى على كلمات محظورة" }, { status: 400 });
    }

    const db = getAdminDb();
    const comments = commentCollection(db, mangaId, chapterUrl);
    const mentions = extractMentions(text);

    // Author snapshot is derived server-side from the verified identity's
    // public profile — client-provided author fields are fallback-only for
    // legacy accounts without a profile doc (same name fallback the app
    // uses: displayName, then username, then generic).
    const profileSnap = await db.collection("publicProfiles").doc(user.uid).get();
    const profile = profileSnap.data();
    const author = resolveAuthorSnapshot(profile, payload);

    const replyId = randomUUID();
    const now = Date.now();

    // Atomic commit: reply doc + root bump together. Notification docs and
    // FCM fan out AFTER the commit (vote pattern) so a notification failure
    // can never roll back the count — that decoupling is the U-19 fix.
    const committed = await db.runTransaction(async (transaction) => {
      let rootRef: DocumentReference;
      let rootAuthorUid: string | null = null;
      let rootAuthorUsername: string | null = null;

      if (parentId) {
        rootRef = comments.doc(parentId);
        const snap = await transaction.get(rootRef);
        if (!snap.exists) throw new ParentNotFoundError();
        const rootData = snap.data();
        if (!rootData) throw new ParentNotFoundError();
        if (rootData.mangaId !== mangaId) throw new ParentNotFoundError();
        // Flat-thread invariant: new replies attach to a top-level root only.
        // A nested parent means the client is writing into a legacy tree and
        // incrementing the wrong doc, so reject instead of misattributing.
        if (typeof rootData.parentId === "string" && rootData.parentId.length > 0) {
          throw new InvalidParentError();
        }
        if (typeof rootData.reviewId === "string" && rootData.reviewId.length > 0) {
          throw new InvalidParentError();
        }
        rootAuthorUid = typeof rootData.authorUid === "string" ? rootData.authorUid : null;
        rootAuthorUsername = typeof rootData.authorUsername === "string" ? rootData.authorUsername : null;
      } else {
        rootRef = db.collection("community_manga").doc(mangaId).collection("reviews").doc(reviewId!);
        const snap = await transaction.get(rootRef);
        if (!snap.exists) throw new ParentNotFoundError();
        const rootData = snap.data();
        if (!rootData) throw new ParentNotFoundError();
        if (rootData.mangaId !== undefined && rootData.mangaId !== mangaId) throw new ParentNotFoundError();
        rootAuthorUid = typeof rootData.authorUid === "string" ? rootData.authorUid : null;
        rootAuthorUsername = typeof rootData.authorUsername === "string" ? rootData.authorUsername : null;
      }

      // Addressee derivation mirrors RepliesScreen + firestore.rules:
      // explicit @mentions override the default root author (null addressee),
      // otherwise the reply addresses the root author. Derived server-side
      // so a spoofed replyToUid can't misdirect the thread.
      const replyToUid = mentions.length > 0 ? null : rootAuthorUid;
      const replyToUsername = mentions.length > 0 ? null : rootAuthorUsername;

      transaction.set(comments.doc(replyId), {
        id: replyId,
        mangaId,
        chapterUrl,
        slug,
        sourceId,
        parentId,
        threadRootId: parentId,
        reviewId,
        replyToUid,
        replyToUsername,
        authorUid: user.uid,
        authorName: author.authorName,
        authorUsername: author.authorUsername,
        authorAvatarUrl: author.authorAvatarUrl,
        authorBadge: author.authorBadge,
        text,
        mentions,
        spoiler,
        isDeleted: false,
        editedAt: null,
        reportedCount: 0,
        createdAt: now,
        replyCount: 0,
        likes: 0,
        dislikes: 0,
      });
      transaction.update(rootRef, { replyCount: FieldValue.increment(1) });

      return { rootAuthorUid };
    });

    // Best-effort fan-out (REPLY + MENTION). Mirrors push-reply recipients
    // so new-path replies notify exactly like old-path ones; failures here
    // must never fail the already-committed reply.
    try {
      await fanOutReplyNotifications({
        db,
        mangaId,
        chapterUrl,
        slug,
        sourceId,
        replyId,
        authorUid: user.uid,
        authorName: author.authorName,
        text,
        mentions,
        rootAuthorUid: committed.rootAuthorUid,
      });
    } catch {
      /* notify failure must never fail the reply */
    }

    return NextResponse.json({ success: true, id: replyId, createdAt: now });
  } catch (error) {
    if (error instanceof ParentNotFoundError) {
      return NextResponse.json({ error: "المحتوى غير موجود" }, { status: 404 });
    }
    if (error instanceof InvalidParentError) {
      return NextResponse.json({ error: "طلب رد غير صالح" }, { status: 400 });
    }
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

class ParentNotFoundError extends Error {}
class InvalidParentError extends Error {}

function commentCollection(db: Firestore, mangaId: string, chapterUrl: string | null) {
  const manga = db.collection("community_manga").doc(mangaId);
  return chapterUrl == null
    ? manga.collection("comments")
    : manga.collection("chapters").doc(stableChapterKey(chapterUrl)).collection("comments");
}

async function fanOutReplyNotifications(args: {
  db: Firestore;
  mangaId: string;
  chapterUrl: string | null;
  slug: string;
  sourceId: string;
  replyId: string;
  authorUid: string;
  authorName: string;
  text: string;
  mentions: string[];
  rootAuthorUid: string | null;
}): Promise<void> {
  const { db, mangaId, chapterUrl, slug, sourceId, replyId, authorUid, authorName, text, mentions, rootAuthorUid } = args;
  const recipients = new Map<string, { type: "REPLY" | "MENTION"; title: string; body: string }>();

  // Mentions are the only address override (push-reply parity). Lookup is
  // case-insensitive per the usernames index; self-mentions are dropped.
  await Promise.all(mentions.map(async (username) => {
    try {
      const target = await db.collection("usernames").doc(username.toLowerCase()).get();
      const targetUid = target.data()?.uid;
      if (typeof targetUid === "string" && targetUid !== authorUid && !recipients.has(targetUid)) {
        recipients.set(targetUid, {
          type: "MENTION",
          title: "تمت الإشارة إليك",
          body: `${authorName} ذكر ${username}`,
        });
      }
    } catch {
      /* one bad lookup must not drop the remaining mentions */
    }
  }));

  // No mentions -> default recipient is the root author (comment or review).
  if (mentions.length === 0 && rootAuthorUid && rootAuthorUid !== authorUid) {
    recipients.set(rootAuthorUid, {
      type: "REPLY",
      title: "رد جديد على تعليقك",
      body: `${authorName}: ${text.slice(0, 80)}`,
    });
  }
  if (recipients.size === 0) return;

  const data = compactData({ mangaId, slug, sourceId, chapterUrl, commentId: replyId });
  await Promise.all([...recipients.entries()].map(async ([targetUid, notification]) => {
    try {
      const notifRef = db.collection("users").doc(targetUid).collection("notifications").doc(randomUUID());
      const devices = await db.collection("users").doc(targetUid).collection("devices").get();
      const tokens = devices.docs
        .map((device) => device.data().token)
        .filter((token): token is string => typeof token === "string");
      await Promise.all([
        notifRef.set({
          id: notifRef.id,
          type: notification.type,
          title: notification.title,
          body: notification.body,
          mangaId,
          slug,
          sourceId,
          chapterUrl,
          commentId: replyId,
          createdAt: Date.now(),
          read: false,
        }),
        (async () => {
          if (tokens.length > 0) {
            await getAdminMessaging().sendEachForMulticast({
              tokens: tokens.slice(0, 500),
              data: { ...data, title: notification.title, body: notification.body, type: notification.type === "MENTION" ? "mention" : "reply" },
            }).catch(() => null);
          }
        })(),
      ]);
    } catch {
      /* per-recipient isolation: one failing target never affects the reply */
    }
  }));
}

function stableChapterKey(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 24);
}

function extractMentions(text: string): string[] {
  const mentions: string[] = [];
  const seen = new Set<string>();
  for (const match of text.matchAll(/@([A-Za-z0-9_]{3,30})/g)) {
    const name = match[1];
    if (!seen.has(name)) {
      seen.add(name);
      mentions.push(name);
      if (mentions.length >= 10) break;
    }
  }
  return mentions;
}

function resolveAuthorSnapshot(
  profile: Record<string, unknown> | undefined,
  payload: Record<string, unknown>
): {
  authorName: string;
  authorUsername: string;
  authorAvatarUrl: string | null;
  authorBadge: string;
} {
  const displayName = typeof profile?.displayName === "string" ? profile.displayName : "";
  const username = typeof profile?.username === "string" ? profile.username : "";
  const avatarUrl = typeof profile?.avatarUrl === "string" ? profile.avatarUrl : null;
  const badgeLabel = typeof profile?.badgeLabel === "string" ? profile.badgeLabel : "";

  // Profile-backed values win; payload fields are fallback-only for legacy
  // accounts without a profile doc. Bounded so a malformed client can't
  // smuggle oversized snapshot strings into the thread.
  const fallbackName = boundedTrimmed(payload.authorName, MAX_AUTHOR_NAME_LENGTH);
  const fallbackUsername = boundedTrimmed(payload.authorUsername, MAX_USERNAME_LENGTH);
  const fallbackAvatar = boundedTrimmed(payload.authorAvatarUrl, MAX_URL_LENGTH);
  const fallbackBadge = boundedTrimmed(payload.authorBadge, MAX_BADGE_LENGTH);

  return {
    authorName: displayName.trim() || username.trim() || fallbackName || "User",
    authorUsername: username.trim() || fallbackUsername || "",
    authorAvatarUrl: avatarUrl ?? fallbackAvatar,
    authorBadge: badgeLabel.trim() || fallbackBadge || "Beginner",
  };
}

function boundedTrimmed(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function normalizeChapterUrl(value: unknown): string | null | "invalid" {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || value.length < 1 || value.length > 4_096) return "invalid";
  return value;
}

/** Optional display strings (slug/sourceId): missing or null -> "". */
function normalizeBoundedString(value: unknown, max: number): string | "invalid" {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.length > max) return "invalid";
  return value;
}

/** Optional target ids: missing or null -> null (the absent side). */
function normalizeTargetId(value: unknown): string | null | "invalid" {
  if (value === undefined || value === null) return null;
  if (!isIdentifier(value)) return "invalid";
  return value;
}

function compactData(values: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => typeof value === "string" && value.length > 0)) as Record<string, string>;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}
