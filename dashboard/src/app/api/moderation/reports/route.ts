import { NextRequest, NextResponse } from "next/server";
import { createHash } from "crypto";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";
import type { ModerationReport } from "@/types/community";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("moderator");
    const snapshot = await getAdminDb().collection("moderationReports")
      .orderBy("createdAt", "desc").limit(100).get();
    const reports = snapshot.docs.map((doc) => toReport(doc.id, doc.data()));
    return NextResponse.json({ reports });
  } catch (error: unknown) {
    return errorResponse(error);
  }
}

export async function PATCH(request: NextRequest) {
  try {
    await requireRole("moderator");
    const body: unknown = await request.json();
    if (!isRecord(body) || !isDocumentId(body.reportId) || !isReportStatus(body.status)) {
      return NextResponse.json({ error: "Invalid params" }, { status: 400 });
    }
    const removeContent = body.removeContent === true;
    if (removeContent && body.status !== "resolved") {
      return NextResponse.json({ error: "Invalid params" }, { status: 400 });
    }
    if (removeContent) {
      // M-3: moderator take-down — soft-delete the reported content
      // server-side (Admin SDK bypasses author-only rules), then resolve.
      const reportSnap = await getAdminDb().collection("moderationReports").doc(body.reportId).get();
      if (!reportSnap.exists) {
        return NextResponse.json({ error: "Invalid params" }, { status: 404 });
      }
      const report = toReport(body.reportId, toRecord(reportSnap.data()));
      await softDeleteTarget(report);
    }
    await getAdminDb().collection("moderationReports").doc(body.reportId).update({ status: body.status });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    return errorResponse(error);
  }
}

/**
 * Soft-delete reported content so it disappears for readers while keeping
 * the thread anchor (mirrors the client soft-delete invariant: cleared text,
 * empty mentions, isDeleted flag).
 */
async function softDeleteTarget(report: ModerationReport): Promise<void> {
  const db = getAdminDb();
  if (report.targetType === "review") {
    if (!report.mangaId || !report.targetId) return;
    await db.collection("community_manga").doc(report.mangaId)
      .collection("reviews").doc(report.targetId)
      .set({ isDeleted: true, body: "", title: "" }, { merge: true });
    return;
  }
  if (!report.mangaId || !report.targetId) return;
  const payload = { isDeleted: true, text: "", mentions: [] as string[] };
  if (report.chapterUrl) {
    const chapterKey = stableChapterKey(report.chapterUrl);
    await db.collection("community_manga").doc(report.mangaId)
      .collection("chapters").doc(chapterKey)
      .collection("comments").doc(report.targetId)
      .set(payload, { merge: true });
  } else {
    await db.collection("community_manga").doc(report.mangaId)
      .collection("comments").doc(report.targetId)
      .set(payload, { merge: true });
  }
}

/** 24 hex chars (96 bits) — must match the app's stableChapterKey (Kotlin). */
function stableChapterKey(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex").slice(0, 24);
}

function toRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function toReport(id: string, data: Record<string, unknown>): ModerationReport {
  return {
    id,
    targetId: text(data.targetId, text(data.commentId)),
    targetType: data.targetType === "review" ? "review" : "comment",
    mangaId: text(data.mangaId),
    chapterUrl: nullableText(data.chapterUrl),
    reportedUid: text(data.reportedUid),
    reporterUid: text(data.reporterUid),
    reason: text(data.reason),
    createdAt: numericValue(data.createdAt),
    status: isStoredStatus(data.status) ? data.status : "open",
    priority: nullableText(data.priority),
  };
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

function isStoredStatus(value: unknown): value is ModerationReport["status"] {
  return value === "open" || value === "resolved" || value === "dismissed";
}

function isReportStatus(value: unknown): value is "resolved" | "dismissed" {
  return value === "resolved" || value === "dismissed";
}

function isDocumentId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 512 && !value.includes("/");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function errorResponse(error: unknown) {
  const { body, status } = genericErrorResponse(error);
  return NextResponse.json(body, { status });
}
