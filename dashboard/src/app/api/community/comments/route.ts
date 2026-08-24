import { createHash } from "crypto";
import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import type { CommunityComment } from "@/types/community";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';
export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { searchParams } = request.nextUrl;
    const mangaId = searchParams.get("mangaId");
    const search = searchParams.get("search")?.trim().toLowerCase() ?? "";
    const limit = boundedLimit(searchParams.get("limit"));
    const db = getAdminDb();
    const query = mangaId
      ? db.collectionGroup("comments").where("mangaId", "==", mangaId).orderBy("createdAt", "desc").limit(limit)
      : db.collectionGroup("comments").orderBy("createdAt", "desc").limit(limit);
    const comments = (await query.get()).docs
      .map((doc) => toComment(doc.id, doc.data()))
      // Soft-deleted rows (text:"", isDeleted:true) must never resurface in moderation lists.
      .filter((comment) => !comment.isDeleted)
      .filter((comment) => !search || matchesSearch(comment, search));

    return NextResponse.json({ comments });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("moderator");
    const body: unknown = await request.json();
    if (!isRecord(body)) {
      return NextResponse.json({ error: "Missing params" }, { status: 400 });
    }
    const commentId = body.commentId;
    const mangaId = body.mangaId;
    const chapterUrl = body.chapterUrl;
    if (!isDocumentId(commentId) || !isDocumentId(mangaId) || !isChapterUrl(chapterUrl)) {
      return NextResponse.json({ error: "Missing params" }, { status: 400 });
    }

    const ref = commentCollection(getAdminDb(), mangaId, chapterUrl).doc(commentId);
    if (!(await ref.get()).exists) {
      return NextResponse.json({ error: "Comment not found" }, { status: 404 });
    }
    // Preserve a root document and its dedicated replies thread instead of orphaning it.
    await ref.update({ text: "", mentions: [], isDeleted: true, editedAt: Date.now() });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

function commentCollection(db: ReturnType<typeof getAdminDb>, mangaId: string, chapterUrl: string | null) {
  const manga = db.collection("community_manga").doc(mangaId);
  return chapterUrl == null
    ? manga.collection("comments")
    : manga.collection("chapters").doc(stableChapterKey(chapterUrl)).collection("comments");
}

function toComment(id: string, data: Record<string, unknown>): CommunityComment {
  return {
    id,
    mangaId: text(data.mangaId),
    chapterUrl: nullableText(data.chapterUrl),
    slug: text(data.slug),
    sourceId: text(data.sourceId),
    parentId: nullableText(data.parentId),
    threadRootId: nullableText(data.threadRootId),
    reviewId: nullableText(data.reviewId),
    replyToUid: nullableText(data.replyToUid),
    replyToUsername: nullableText(data.replyToUsername),
    authorUid: text(data.authorUid),
    authorName: text(data.authorName, "مجهول"),
    authorUsername: nullableText(data.authorUsername),
    authorAvatarUrl: nullableText(data.authorAvatarUrl),
    authorBadge: nullableText(data.authorBadge),
    text: text(data.text),
    mentions: textList(data.mentions),
    spoiler: boolean(data.spoiler),
    isDeleted: boolean(data.isDeleted),
    editedAt: nullableNumber(data.editedAt),
    reportedCount: numericValue(data.reportedCount),
    createdAt: numericValue(data.createdAt),
    replyCount: numericValue(data.replyCount),
    likes: numericValue(data.likes),
    dislikes: numericValue(data.dislikes),
  };
}

function matchesSearch(comment: CommunityComment, search: string): boolean {
  return [comment.text, comment.authorName, comment.authorUsername, comment.mangaId]
    .filter((value): value is string => value != null)
    .some((value) => value.toLowerCase().includes(search));
}

function boundedLimit(raw: string | null): number {
  const parsed = Number(raw);
  return Number.isInteger(parsed) ? Math.min(Math.max(parsed, 1), 100) : 50;
}

function isDocumentId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}

function isChapterUrl(value: unknown): value is string | null {
  return value === null || (typeof value === "string" && value.length <= 4_096);
}

function stableChapterKey(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 24);
}

function text(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function nullableText(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function numericValue(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) return Math.floor(value);
  if (value && typeof value === "object" && "toMillis" in value && typeof value.toMillis === "function") {
    return value.toMillis();
  }
  return 0;
}

function nullableNumber(value: unknown): number | null {
  return value == null ? null : numericValue(value);
}

function boolean(value: unknown): boolean {
  return value === true;
}

function textList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string") : [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
