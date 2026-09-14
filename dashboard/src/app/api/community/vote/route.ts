import { NextRequest, NextResponse } from "next/server";
import { randomUUID } from "crypto";
import { type DocumentReference } from "firebase-admin/firestore";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";
import { computeVoteTransition, isVote } from "@/lib/vote-transition";

export const dynamic = "force-dynamic";

type TargetType = "comment" | "review";

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
    if (!(await allowAppMutation(`community-vote:${user.uid}`, 120, 60 * 1000))){
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }

    const payload = await request.json();
    // Keep commentId support for older app releases while new clients use a typed target.
    const targetType: TargetType = payload.targetType === "review" ? "review" : "comment";
    const targetId = typeof payload.targetId === "string" ? payload.targetId : payload.commentId;
    const mangaId = payload.mangaId;
    if (!isIdentifier(targetId) || !isVote(payload.vote) || (targetType === "review" && !isIdentifier(mangaId))) {
      return NextResponse.json({ error: "طلب تصويت غير صالح" }, { status: 400 });
    }

    const db = getAdminDb();
    let contentRef: DocumentReference;
    if (targetType === "review") {
      contentRef = db.collection("community_manga").doc(mangaId).collection("reviews").doc(targetId);
    } else {
      const comments = await db.collectionGroup("comments")
        .where("id", "==", targetId)
        .limit(2)
        .get();
      if (comments.empty) throw new ContentNotFoundError();
      if (comments.size > 1) {
        return NextResponse.json({ error: "معرف التعليق غير واضح" }, { status: 409 });
      }
      contentRef = comments.docs[0].ref;
    }

    const voteRef = contentRef.collection("votes").doc(user.uid);
    const result = await db.runTransaction(async (transaction) => {
      const [contentSnapshot, voteSnapshot] = await Promise.all([
        transaction.get(contentRef),
        transaction.get(voteRef),
      ]);
      const content = contentSnapshot.data();
      if (!content) throw new ContentNotFoundError();
      if (content.authorUid === user.uid) throw new SelfVoteError();

      // Toggle semantics: repeat retracts, opposite switches, fresh adds.
      // Pure math lives in lib/vote-transition (unit-tested, no I/O).
      const transition = computeVoteTransition(
        voteSnapshot.data()?.value,
        payload.vote,
        content.likes,
        content.dislikes
      );
      if (transition.removeVoteDoc) {
        transaction.delete(voteRef);
      } else {
        transaction.set(voteRef, { uid: user.uid, value: payload.vote, updatedAt: Date.now() });
      }
      transaction.update(contentRef, { likes: transition.likes, dislikes: transition.dislikes });
      return { likes: transition.likes, dislikes: transition.dislikes, changed: transition.changed, action: transition.action };
    });

    // myVote mirrors the transition for the client's optimistic echo: the
    // retracted vote reads back as "no vote" (null), anything else echoes
    // the cast value.

    // Like/dislike fan-out (best-effort — the vote is already committed).
    // Clients cannot write to another user's notifications (owner-only rule),
    // so the server creates the REVIEW_REACTION doc + FCM via Admin SDK.
    // Retractions stay silent — nobody needs a "like removed" ping.
    if (result.action !== "removed") {
      try {
        const contentSnap = await contentRef.get();
        const contentData = contentSnap.data();
        const authorUid = contentData?.authorUid as string | undefined;
        if (authorUid && authorUid !== user.uid) {
          const voterProfile = (await db.collection("publicProfiles").doc(user.uid).get()).data();
          const voterName = String(voterProfile?.displayName || voterProfile?.username || "مستخدم");
          const isLike = payload.vote === 1;
          const contentMangaId = targetType === "review"
            ? String(mangaId)
            : String(contentData?.mangaId ?? "");
          const notifRef = db.collection("users").doc(authorUid).collection("notifications").doc(randomUUID());
          await notifRef.set({
            id: notifRef.id,
            type: "REVIEW_REACTION",
            title: isLike ? "إعجاب جديد" : "تقييم جديد",
            body: `${voterName} ${isLike ? "أعجب" : "قيّم"} ${targetType === "review" ? "بمراجعتك" : "بتعليقك"}`,
            mangaId: contentMangaId,
            slug: String(contentData?.slug ?? ""),
            sourceId: String(contentData?.sourceId ?? ""),
            chapterUrl: (contentData?.chapterUrl as string | null) ?? null,
            commentId: targetType === "comment" ? String(targetId) : null,
            createdAt: Date.now(),
            read: false,
          });
          const devices = await db.collection("users").doc(authorUid).collection("devices").get();
          const tokens = devices.docs
            .map((d) => d.data().token)
            .filter((t): t is string => typeof t === "string");
          if (tokens.length > 0) {
            await getAdminMessaging().sendEachForMulticast({
              tokens: tokens.slice(0, 500),
              data: {
                title: isLike ? "إعجاب جديد" : "تقييم جديد",
                body: `${voterName} تفاعل مع المحتوى الخاص بك`,
                type: "REVIEW_REACTION",
                ...(contentMangaId ? { mangaId: contentMangaId } : {}),
              },
            }).catch(() => null);
          }
        }
      } catch {
        /* notify failure must never fail the vote */
      }
    }

    return NextResponse.json({
      success: true,
      ...result,
      myVote: result.action === "removed" ? null : payload.vote,
    });
  } catch (error) {
    if (error instanceof ContentNotFoundError) {
      return NextResponse.json({ error: "المحتوى غير موجود" }, { status: 404 });
    }
    if (error instanceof SelfVoteError) {
      return NextResponse.json({ error: "لا يمكنك التصويت على المحتوى الخاص بك" }, { status: 403 });
    }
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

class ContentNotFoundError extends Error {}
class SelfVoteError extends Error {}

function isIdentifier(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}
