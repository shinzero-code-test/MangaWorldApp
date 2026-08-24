import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import type { MangaReview } from "@/types/community";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { searchParams } = request.nextUrl;
    const mangaId = searchParams.get("mangaId");
    const limit = boundedLimit(searchParams.get("limit"));

    let reviews: MangaReview[];
    if (mangaId) {
      const snap = await getAdminDb().collection("community_manga").doc(mangaId).collection("reviews").orderBy("createdAt", "desc").limit(limit).get();
      reviews = snap.docs.map((doc) => toReview(doc.id, doc.data(), mangaId));
    } else {
      const snap = await getAdminDb().collectionGroup("reviews").orderBy("createdAt", "desc").limit(limit).get();
      reviews = snap.docs.map((doc) => toReview(doc.id, doc.data()));
    }
    // Soft-deleted reviews (title/body cleared) must not reappear or skew rating averages.
    reviews = reviews.filter((r) => !r.isDeleted);

    return NextResponse.json({ reviews });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("moderator");
    const body: unknown = await request.json();
    if (!isRecord(body) || !isDocumentId(body.reviewId) || !isDocumentId(body.mangaId)) {
      return NextResponse.json({ error: "Missing params" }, { status: 400 });
    }
    const ref = getAdminDb().collection("community_manga").doc(body.mangaId).collection("reviews").doc(body.reviewId);
    if (!(await ref.get()).exists) {
      return NextResponse.json({ error: "Review not found" }, { status: 404 });
    }
    // Retain the document so existing replies remain anchored to this review.
    await ref.update({ title: "", body: "", isDeleted: true, updatedAt: Date.now() });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

function toReview(id: string, data: Record<string, unknown>, mangaIdFallback = ""): MangaReview {
  return {
    id,
    mangaId: text(data.mangaId, mangaIdFallback),
    authorUid: text(data.authorUid),
    authorName: text(data.authorName, "مجهول"),
    authorUsername: nullableText(data.authorUsername),
    authorAvatarUrl: nullableText(data.authorAvatarUrl),
    authorBadge: nullableText(data.authorBadge),
    rating: numericValue(data.rating),
    title: text(data.title),
    body: text(data.body),
    createdAt: numericValue(data.createdAt),
    updatedAt: numericValue(data.updatedAt) || numericValue(data.createdAt),
    replyCount: numericValue(data.replyCount),
    likes: numericValue(data.likes),
    dislikes: numericValue(data.dislikes),
    reportedCount: numericValue(data.reportedCount),
    isDeleted: data.isDeleted === true,
  };
}

function boundedLimit(raw: string | null): number {
  const parsed = Number(raw);
  return Number.isInteger(parsed) ? Math.min(Math.max(parsed, 1), 100) : 50;
}

function isDocumentId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
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
