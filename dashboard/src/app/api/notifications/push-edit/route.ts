import { createHash, randomUUID } from "crypto";
import { NextRequest, NextResponse } from "next/server";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

const MENTION_RE = /@([A-Za-z0-9_]{3,30})/g;
const MAX_MENTION_RECIPIENTS = 10;

/**
 * Edit-mention fan-out. `updateComment` / `upsertReview` call here best-effort
 * after a successful edit. The original `push-reply` dispatch is deduped by
 * comment ID, so edits would otherwise never notify — this route diffs the
 * CURRENT @mentions against `commentNotificationDispatches.notifiedMentions`
 * and notifies only freshly-added mentions.
 *
 * Reviews have no stored `mentions` field (rules), so mentions are extracted
 * server-side from `title + body`. Comments use the live doc's `text`.
 */
export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
    if (!(await allowAppMutation(`comment-edit-notify:${user.uid}`, 60, 60 * 1000))) {
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }
    const { mangaId, chapterUrl, commentId, reviewId } = await request.json();
    if (typeof mangaId !== "string" || mangaId.length < 1 || mangaId.length > 512 || mangaId.includes("/")) {
      return NextResponse.json({ error: "حدث تعليق غير صالح" }, { status: 400 });
    }
    if (
      (commentId !== undefined && (typeof commentId !== "string" || !isIdentifier(commentId))) ||
      (reviewId !== undefined && (typeof reviewId !== "string" || !isIdentifier(reviewId))) ||
      (chapterUrl !== undefined && (typeof chapterUrl !== "string" || chapterUrl.length < 1 || chapterUrl.length > 4096))
    ) {
      return NextResponse.json({ error: "حدث تعليق غير صالح" }, { status: 400 });
    }
    if (typeof commentId !== "string" && typeof reviewId !== "string") {
      return NextResponse.json({ error: "حدث تعليق غير صالح" }, { status: 400 });
    }

    const db = getAdminDb();
    let text = "";
    let authorName = "مشاهد";
    let slug = "";
    let sourceId = "";

    if (typeof commentId === "string") {
      const manga = db.collection("community_manga").doc(mangaId);
      const col =
        chapterUrl !== undefined
          ? manga.collection("chapters").doc(stableChapterKey(String(chapterUrl))).collection("comments")
          : manga.collection("comments");
      const snap = await col.doc(commentId).get();
      const d = snap.data();
      if (!d || d.authorUid !== user.uid) {
        return NextResponse.json({ error: "التعليق غير موجود" }, { status: 404 });
      }
      text = String(d.text ?? "");
      authorName = String(d.authorName ?? authorName);
      slug = String(d.slug ?? "");
      sourceId = String(d.sourceId ?? "");
    } else {
      const snap = await db
        .collection("community_manga")
        .doc(mangaId)
        .collection("reviews")
        .doc(String(reviewId))
        .get();
      const d = snap.data();
      if (!d || d.authorUid !== user.uid) {
        return NextResponse.json({ error: "المراجعة غير موجودة" }, { status: 404 });
      }
      text = `${String(d.title ?? "")}\n${String(d.body ?? "")}`;
      authorName = String(d.authorName ?? authorName);
    }

    const current = [...new Set([...text.matchAll(MENTION_RE)].map((m) => m[1].toLowerCase()))].slice(
      0,
      MAX_MENTION_RECIPIENTS
    );
    const dispatchKey = `${mangaId}:${(chapterUrl as string | undefined) ?? ""}:${(commentId as string | undefined) ?? (reviewId as string)}`;
    const dispatchRef = db
      .collection("commentNotificationDispatches")
      .doc(createHash("sha256").update(dispatchKey).digest("hex"));
    const dispatchSnap = await dispatchRef.get();
    const already = new Set((dispatchSnap.data()?.notifiedMentions as string[] | undefined) ?? []);
    const fresh = current.filter((m) => !already.has(m));
    if (fresh.length === 0) return NextResponse.json({ success: true, sent: 0 });

    let sent = 0;
    for (const username of fresh) {
      try {
        const target = await db.collection("usernames").doc(username).get();
        const targetUid = target.data()?.uid;
        if (typeof targetUid !== "string" || targetUid === user.uid) continue;
        const ref = db.collection("users").doc(targetUid).collection("notifications").doc(randomUUID());
        await ref.set({
          id: ref.id,
          type: "MENTION",
          title: "تمت الإشارة إليك",
          body: `${authorName} ذكر ${username} في تعديل`,
          mangaId,
          slug,
          sourceId,
          chapterUrl: (chapterUrl as string | undefined) ?? null,
          commentId: (commentId as string | undefined) ?? null,
          createdAt: Date.now(),
          read: false,
        });
        const devices = await db.collection("users").doc(targetUid).collection("devices").get();
        const tokens = devices.docs
          .map((d) => d.data().token)
          .filter((t): t is string => typeof t === "string");
        if (tokens.length > 0) {
          const res = await getAdminMessaging().sendEachForMulticast({
            tokens: tokens.slice(0, 500),
            data: {
              title: "تمت الإشارة إليك",
              body: `${authorName} ذكرك في تعديل`,
              type: "mention",
              mangaId,
              ...((commentId as string | undefined) ? { commentId: String(commentId) } : {}),
            },
          });
          sent += res.successCount;
        } else {
          sent += 1; // doc written, no device to push to
        }
      } catch {
        /* per-recipient isolation — one bad target must not fail the batch */
      }
    }
    await dispatchRef.set(
      { authorUid: user.uid, createdAt: Date.now(), notifiedMentions: [...already, ...fresh] },
      { merge: true }
    );
    return NextResponse.json({ success: true, sent });
  } catch (error) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

function stableChapterKey(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 24);
}

function isIdentifier(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}
