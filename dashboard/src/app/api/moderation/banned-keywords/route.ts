import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("super-admin");
    const doc = await adminDb.collection("app_config").doc("banned_keywords").get();
    return NextResponse.json({ keywords: doc.data()?.keywords || "" });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { keywords } = await request.json();
    await adminDb.collection("app_config").doc("banned_keywords").set({
      keywords, updatedAt: Date.now(),
    }, { merge: true });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
