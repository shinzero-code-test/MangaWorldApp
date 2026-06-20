import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { adminDb } from "@/lib/firebase-admin";

export async function GET(request: NextRequest) {
  try {
    await requireRole("viewer");
    const { searchParams } = new URL(request.url);
    const period = searchParams.get("period") || "7d";

    // Get real data from Firestore
    const [profilesSnap, reportsSnap, listsSnap] = await Promise.all([
      adminDb.collection("publicProfiles").count().get(),
      adminDb.collection("moderationReports").where("status", "==", "open").count().get(),
      adminDb.collectionGroup("lists").count().get(),
    ]);

    // Get role distribution
    const roleCounts = { "super-admin": 0, moderator: 0, viewer: 0 };
    const profiles = await adminDb.collection("publicProfiles").limit(100).get();
    profiles.docs.forEach((doc: any) => {
      const role = doc.data().role || "viewer";
      if (role in roleCounts) roleCounts[role as keyof typeof roleCounts]++;
    });

    // Get recent sign-ups (last 7 days)
    const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const recentProfiles = await adminDb.collection("publicProfiles")
      .where("updatedAt", ">=", weekAgo).count().get();

    // Mock engagement data (would come from Firebase Analytics in production)
    const dailyActive = [
      { date: "Sat", users: 45 }, { date: "Sun", users: 38 },
      { date: "Mon", users: 52 }, { date: "Tue", users: 48 },
      { date: "Wed", users: 61 }, { date: "Thu", users: 55 },
      { date: "Fri", users: 42 },
    ];

    const sourceUsage = [
      { name: "Olympus", value: 35, color: "#6366f1" },
      { name: "Azora", value: 25, color: "#22c55e" },
      { name: "Starz", value: 20, color: "#f59e0b" },
      { name: "MangaSid", value: 12, color: "#a855f7" },
      { name: "Meshmanga", value: 8, color: "#ef4444" },
    ];

    return NextResponse.json({
      overview: {
        totalUsers: profilesSnap.data().count,
        openReports: reportsSnap.data().count,
        totalLists: listsSnap.data().count,
        recentSignUps: recentProfiles.data().count,
        roleDistribution: roleCounts,
      },
      engagement: {
        dailyActive,
        sourceUsage,
        avgReadingTime: 23,
        retentionRate: 68,
        avgPagesPerSession: 42,
      },
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
