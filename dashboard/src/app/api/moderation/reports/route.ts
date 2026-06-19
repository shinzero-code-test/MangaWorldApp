import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("moderator");
    const snapshot = await adminDb.collection("moderationReports")
      .orderBy("createdAt", "desc").limit(100).get();
    const reports = snapshot.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
    return NextResponse.json({ reports });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}

export async function PATCH(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { reportId, status } = await request.json();
    if (!reportId || !["resolved", "dismissed"].includes(status)) {
      return NextResponse.json({ error: "Invalid params" }, { status: 400 });
    }
    await adminDb.collection("moderationReports").doc(reportId).update({ status });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
