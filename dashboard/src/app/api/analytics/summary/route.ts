import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("viewer");
    const [profiles, reports] = await Promise.all([
      adminDb.collection("publicProfiles").count().get(),
      adminDb.collection("moderationReports").where("status", "==", "open").count().get(),
    ]);
    return NextResponse.json({
      totalUsers: profiles.data().count,
      totalComments: 0,
      totalReviews: 0,
      openReports: reports.data().count,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
