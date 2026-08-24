import { createHash, randomUUID } from "crypto";
import { FieldValue, type CollectionReference, type Firestore } from "firebase-admin/firestore";
import { NextRequest, NextResponse } from "next/server";
import { verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";
const MAX_MENTION_RECIPIENTS = 10;

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    if (!(await allowAppMutation(`comment-notification:${user.uid}`, 60, 60 * 60 * 1000))){
      return NextResponse.json({ error: "Too many notification requests" }, { status: 429 });
    }
    const { mangaId, chapterUrl, commentId } = await request.json();
    if (!isIdentifier(mangaId) || !isIdentifier(commentId) || !isValidChapterUrl(chapterUrl)) {
      return NextResponse.json({ error: "A valid comment event is required" }, { status: 400 });
    }

    const db = getAdminDb();
    const comments = commentCollection(db, mangaId, chapterUrl);
    const commentRef = comments.doc(commentId);
    const commentSnapshot = await commentRef.get();
    const comment = commentSnapshot.data();
    if (!comment || comment.authorUid !== user.uid) {
      return NextResponse.json({ error: "Comment not found" }, { status: 404 });
    }

    const recipients = new Map<string, { type: "REPLY" | "MENTION"; title: string; body: string }>();
    const parentId = typeof comment.parentId === "string" ? comment.parentId : null;
    const threadRootId = isIdentifier(comment.threadRootId) ? comment.threadRootId : parentId;
    const reviewId = isIdentifier(comment.reviewId) ? comment.reviewId : null;
    const replyBody = `${String(comment.authorName ?? "مشاهد")}: ${String(comment.text ?? "").slice(0, 80)}`;
    const addReplyRecipient = (targetUid: unknown) => {
      if (typeof targetUid === "string" && targetUid !== user.uid) {
        recipients.set(targetUid, {
          type: "REPLY",
          title: "رد جديد على تعليقك",
          body: replyBody,
        });
      }
    };

    const mentions = Array.isArray(comment.mentions)
      ? [...new Set(comment.mentions.filter((name): name is string => typeof name === "string"))].slice(0, MAX_MENTION_RECIPIENTS)
      : [];
    await Promise.all(mentions.map(async (username) => {
      const target = await db.collection("usernames").doc(username.toLowerCase()).get();
      const targetUid = target.data()?.uid;
      if (typeof targetUid === "string" && targetUid !== user.uid && !recipients.has(targetUid)) {
        recipients.set(targetUid, {
          type: "MENTION",
          title: "تمت الإشارة إليك",
          body: `${String(comment.authorName ?? "مشاهد")} ذكر ${username}`,
        });
      }
    }));
    // An explicit @mention is the only way to override the default recipient. Otherwise derive
    // the root author from Firestore rather than trusting client-provided replyToUid metadata.
    let rootCommentId: string | null = null;
    if (mentions.length === 0 && reviewId) {
      const review = await db.collection("community_manga").doc(mangaId).collection("reviews").doc(reviewId).get();
      addReplyRecipient(review.data()?.authorUid);
    } else if (mentions.length === 0 && threadRootId) {
      // New replies carry threadRootId. Follow legacy parent chains as well so old nested
      // comments still notify and count against the true root author.
      const root = await resolveCommentThreadRoot(comments, threadRootId);
      if (root.exists) {
        rootCommentId = root.id;
        addReplyRecipient(root.data()?.authorUid);
      }
    } else if (threadRootId) {
      const root = await resolveCommentThreadRoot(comments, threadRootId);
      if (root.exists) rootCommentId = root.id;
    }
    const dispatchRef = db.collection("commentNotificationDispatches").doc(dispatchId(mangaId, chapterUrl, commentId));
    const slug = typeof comment.slug === "string" ? comment.slug : "";
    const sourceId = typeof comment.sourceId === "string" ? comment.sourceId : "";
    const notificationEntries = [...recipients.entries()].map(([targetUid, notification]) => ({
      targetUid,
      notification,
      ref: db.collection("users").doc(targetUid).collection("notifications").doc(randomUUID()),
    }));
    const shouldDispatch = await db.runTransaction(async (transaction) => {
      if ((await transaction.get(dispatchRef)).exists) return false;
      transaction.create(dispatchRef, { authorUid: user.uid, createdAt: Date.now() });
      if (reviewId) {
        transaction.update(
          db.collection("community_manga").doc(mangaId).collection("reviews").doc(reviewId),
          { replyCount: FieldValue.increment(1) },
        );
      } else if (rootCommentId) {
        transaction.update(comments.doc(rootCommentId), { replyCount: FieldValue.increment(1) });
      }
      notificationEntries.forEach(({ ref, notification }) => {
        transaction.set(ref, {
          type: notification.type,
          title: notification.title,
          body: notification.body,
          mangaId,
          slug,
          sourceId,
          chapterUrl: chapterUrl ?? null,
          commentId,
          createdAt: Date.now(),
          read: false,
        });
      });
      return true;
    });
    if (!shouldDispatch) return NextResponse.json({ success: true, sent: 0 });
    if (recipients.size === 0) return NextResponse.json({ success: true, sent: 0 });

    const data = compactData({ mangaId, slug, sourceId, chapterUrl, commentId });
    const sent = await Promise.all([...recipients.entries()].map(async ([targetUid, notification]) => {
      const devices = await db.collection("users").doc(targetUid).collection("devices").get();
      const tokens = devices.docs.map((device) => device.data().token).filter((token): token is string => typeof token === "string");
      return sendPush(tokens, notification.title, notification.body, data);
    }));
    return NextResponse.json({ success: true, sent: sent.reduce((total, count) => total + count, 0) });
  } catch (error) {
    console.error("Comment notification error:", error);
    return NextResponse.json({ error: "Unable to send notification" }, { status: 401 });
  }
}

function commentCollection(db: Firestore, mangaId: string, chapterUrl?: string) {
  const manga = db.collection("community_manga").doc(mangaId);
  return chapterUrl !== undefined
    ? manga.collection("chapters").doc(stableChapterKey(chapterUrl)).collection("comments")
    : manga.collection("comments");
}

async function resolveCommentThreadRoot(
  comments: CollectionReference,
  initialId: string,
) {
  let currentId = initialId;
  for (let depth = 0; depth < 10; depth += 1) {
    const snapshot = await comments.doc(currentId).get();
    if (!snapshot.exists) return snapshot;
    const parentId = snapshot.data()?.parentId;
    if (!isIdentifier(parentId)) return snapshot;
    currentId = parentId;
  }
  return comments.doc(currentId).get();
}

async function sendPush(tokens: string[], title: string, body: string, data: Record<string, string>): Promise<number> {
  let sent = 0;
  for (let index = 0; index < tokens.length; index += 500) {
    const response = await getAdminMessaging().sendEachForMulticast({ tokens: tokens.slice(index, index + 500), notification: { title, body }, data });
    sent += response.successCount;
  }
  return sent;
}

function isValidChapterUrl(value: unknown): value is string | undefined {
  return value === undefined || (typeof value === "string" && value.length >= 1 && value.length <= 4_096);
}

function compactData(values: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => typeof value === "string" && value.length > 0)) as Record<string, string>;
}

function stableChapterKey(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 24);
}

function dispatchId(mangaId: string, chapterUrl: string | undefined, commentId: string): string {
  return createHash("sha256").update(`${mangaId}:${chapterUrl ?? ""}:${commentId}`).digest("hex");
}

function isIdentifier(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}
