import { createHash, randomUUID } from "crypto";
import { FieldValue, type Firestore } from "firebase-admin/firestore";
import { NextRequest, NextResponse } from "next/server";
import { verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    if (!allowAppMutation(`comment-notification:${user.uid}`, 60, 60 * 60 * 1000)) {
      return NextResponse.json({ error: "Too many notification requests" }, { status: 429 });
    }
    const { mangaId, chapterUrl, commentId } = await request.json();
    if (!isIdentifier(mangaId) || !isIdentifier(commentId) || (chapterUrl !== undefined && typeof chapterUrl !== "string")) {
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
    if (parentId) {
      const parent = await comments.doc(parentId).get();
      const parentUid = parent.data()?.authorUid;
      if (typeof parentUid === "string" && parentUid !== user.uid) {
        recipients.set(parentUid, {
          type: "REPLY",
          title: "رد جديد على تعليقك",
          body: `${String(comment.authorName ?? "مشاهد")}: ${String(comment.text ?? "").slice(0, 80)}`,
        });
      }
    }
    const mentions = Array.isArray(comment.mentions) ? comment.mentions.filter((name): name is string => typeof name === "string") : [];
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
    if (recipients.size === 0) return NextResponse.json({ success: true, sent: 0 });

    const dispatchRef = db.collection("commentNotificationDispatches").doc(dispatchId(mangaId, chapterUrl, commentId));
    const shouldDispatch = await db.runTransaction(async (transaction) => {
      if ((await transaction.get(dispatchRef)).exists) return false;
      transaction.create(dispatchRef, { authorUid: user.uid, createdAt: Date.now() });
      if (parentId) transaction.update(comments.doc(parentId), { replyCount: FieldValue.increment(1) });
      return true;
    });
    if (!shouldDispatch) return NextResponse.json({ success: true, sent: 0 });

    const slug = typeof comment.slug === "string" ? comment.slug : "";
    const sourceId = typeof comment.sourceId === "string" ? comment.sourceId : "";
    const notificationBatch = db.batch();
    for (const [targetUid, notification] of recipients) {
      notificationBatch.set(db.collection("users").doc(targetUid).collection("notifications").doc(randomUUID()), {
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
    }
    await notificationBatch.commit();

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
  return chapterUrl
    ? manga.collection("chapters").doc(stableChapterKey(chapterUrl)).collection("comments")
    : manga.collection("comments");
}

async function sendPush(tokens: string[], title: string, body: string, data: Record<string, string>): Promise<number> {
  let sent = 0;
  for (let index = 0; index < tokens.length; index += 500) {
    const response = await getAdminMessaging().sendEachForMulticast({ tokens: tokens.slice(index, index + 500), notification: { title, body }, data });
    sent += response.successCount;
  }
  return sent;
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
