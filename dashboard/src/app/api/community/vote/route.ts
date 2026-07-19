import { NextRequest, NextResponse } from "next/server";
import { verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    if (!allowAppMutation(`comment-vote:${user.uid}`, 120, 60 * 1000)) {
      return NextResponse.json({ error: "Too many vote requests" }, { status: 429 });
    }

    const { commentId, vote } = await request.json();
    if (!isCommentId(commentId) || !isVote(vote)) {
      return NextResponse.json({ error: "A valid comment vote is required" }, { status: 400 });
    }

    const db = getAdminDb();
    const comments = await db.collectionGroup("comments")
      .where("id", "==", commentId)
      .limit(2)
      .get();
    if (comments.empty) {
      return NextResponse.json({ error: "Comment not found" }, { status: 404 });
    }
    if (comments.size > 1) {
      return NextResponse.json({ error: "Ambiguous comment identifier" }, { status: 409 });
    }

    const commentRef = comments.docs[0].ref;
    const voteRef = commentRef.collection("votes").doc(user.uid);
    const result = await db.runTransaction(async (transaction) => {
      const [commentSnapshot, voteSnapshot] = await Promise.all([
        transaction.get(commentRef),
        transaction.get(voteRef),
      ]);
      const comment = commentSnapshot.data();
      if (!comment) throw new CommentNotFoundError();
      if (comment.authorUid === user.uid) throw new SelfVoteError();

      const previousVote = voteSnapshot.data()?.value;
      if (previousVote === vote) {
        return { likes: nonNegativeCount(comment.likes), dislikes: nonNegativeCount(comment.dislikes), changed: false };
      }

      const likes = Math.max(0, nonNegativeCount(comment.likes) + voteDelta(previousVote, vote, 1));
      const dislikes = Math.max(0, nonNegativeCount(comment.dislikes) + voteDelta(previousVote, vote, -1));
      transaction.set(voteRef, { uid: user.uid, value: vote, updatedAt: Date.now() });
      transaction.update(commentRef, { likes, dislikes });
      return { likes, dislikes, changed: true };
    });

    return NextResponse.json({ success: true, ...result });
  } catch (error) {
    if (error instanceof CommentNotFoundError) {
      return NextResponse.json({ error: "Comment not found" }, { status: 404 });
    }
    if (error instanceof SelfVoteError) {
      return NextResponse.json({ error: "You cannot vote on your own comment" }, { status: 403 });
    }
    console.error("Comment vote error:", error);
    return NextResponse.json({ error: "Unable to update comment vote" }, { status: 401 });
  }
}

class CommentNotFoundError extends Error {}
class SelfVoteError extends Error {}

function isCommentId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9-]{1,128}$/.test(value);
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
