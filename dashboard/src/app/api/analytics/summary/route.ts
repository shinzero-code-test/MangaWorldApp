import { NextRequest, NextResponse } from "next/server";
import { getDashboardRoleCounts, requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    await requireRole("viewer");
    const { searchParams } = new URL(request.url);
    const period = searchParams.get("period") || "7d";

    // Get real data from Firestore
    const [profilesSnap, reportsSnap, listsSnap] = await Promise.all([
      getAdminDb().collection("publicProfiles").count().get(),
      getAdminDb().collection("moderationReports").where("status", "==", "open").count().get(),
      getAdminDb().collectionGroup("lists").count().get(),
    ]);

    const roleCounts = await getDashboardRoleCounts();

    // Get recent sign-ups (last 7 days)
    const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const recentProfiles = await getAdminDb().collection("publicProfiles")
      .where("updatedAt", ">=", weekAgo).count().get();

    // Real daily active users from Firestore
    const dailyActive = [];
    for (let i = 6; i >= 0; i--) {
      const d = new Date();
      d.setDate(d.getDate() - i);
      d.setHours(0, 0, 0, 0);
      const start = d.getTime();
      const end = start + 86400000;
      try {
        const snap = await getAdminDb().collection("publicProfiles")
          .where("updatedAt", ">=", start).where("updatedAt", "<", end).count().get();
        dailyActive.push({ date: d.toLocaleDateString('ar-EG', { weekday: 'short' }), users: snap.data().count });
      } catch {
        dailyActive.push({ date: d.toLocaleDateString('ar-EG', { weekday: 'short' }), users: 0 });
      }
    }

    // Source usage from community manga
    const sourceCounts: Record<string, number> = {};
    try {
      const mangasSnap = await getAdminDb().collection("community_manga").limit(200).get();
      mangasSnap.docs.forEach((doc: any) => {
        const s = doc.data().source || "Unknown";
        sourceCounts[s] = (sourceCounts[s] || 0) + 1;
      });
    } catch { /* ignore if collection doesn't exist */ }
    
    const sourceUsage = (Object.entries(sourceCounts) || [])
      .map(([name, value], i) => {
        const colors = ["#6366f1", "#22c55e", "#f59e0b", "#a855f7", "#ef4444"];
        return { name, value, color: colors[i % colors.length] };
      })
      .sort((a, b) => b.value - a.value)
      .slice(0, 5);

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
        avgReadingTime: 0,
        retentionRate: 0,
        avgPagesPerSession: 0,
      },
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
