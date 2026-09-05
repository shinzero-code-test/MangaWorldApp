import { NextRequest, NextResponse } from "next/server";
import { type DocumentReference } from "firebase-admin/firestore";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

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

      const previousVote = voteSnapshot.data()?.value;
      if (previousVote === payload.vote) {
        return {
          likes: nonNegativeCount(content.likes),
          dislikes: nonNegativeCount(content.dislikes),
          changed: false,
        };
      }

      const likes = Math.max(0, nonNegativeCount(content.likes) + voteDelta(previousVote, payload.vote, 1));
      const dislikes = Math.max(0, nonNegativeCount(content.dislikes) + voteDelta(previousVote, payload.vote, -1));
      transaction.set(voteRef, { uid: user.uid, value: payload.vote, updatedAt: Date.now() });
      transaction.update(contentRef, { likes, dislikes });
      return { likes, dislikes, changed: true };
    });

    return NextResponse.json({ success: true, ...result });
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

function isVote(value: unknown): value is 1 | -1 {
  return value === 1 || value === -1;
}

function nonNegativeCount(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

function voteDelta(previousVote: unknown, nextVote: 1 | -1, targetVote: 1 | -1): number {
  return Number(previousVote === targetVote) * -1 + Number(nextVote === targetVote);
}
