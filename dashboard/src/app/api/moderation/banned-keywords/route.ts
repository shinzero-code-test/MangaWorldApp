import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { boundedString } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const doc = await getAdminDb().collection("app_config").doc("banned_keywords").get();
    return NextResponse.json({ keywords: doc.data()?.keywords || "" });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const body = await request.json();
    // Comma-separated keyword list; bounded so a stray payload can't bloat app config.
    const keywords = boundedString(body?.keywords, 10_000);
    if (keywords === null) {
      return NextResponse.json({ error: "keywords must be a non-empty string (max 10k chars)" }, { status: 400 });
    }
    await getAdminDb().collection("app_config").doc("banned_keywords").set({
      keywords, updatedAt: Date.now(),
    }, { merge: true });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
