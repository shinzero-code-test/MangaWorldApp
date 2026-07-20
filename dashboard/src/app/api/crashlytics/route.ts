import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    await requireRole("super-admin");

    // Read crash reports from Firestore crash_reports collection
    // (populated by a Cloud Function or app-side crash reporter)
    let issues: any[] = [];
    try {
      const snap = await getAdminDb()
        .collection("crash_reports")
        .orderBy("lastOccurrence", "desc")
        .limit(50)
        .get();
      issues = snap.docs.map((doc) => {
        const d = doc.data();
        return {
          id: doc.id,
          title: d.title || "Unknown crash",
          subtitle: d.subtitle || "",
          state: d.state || "open",
          count: d.count || 0,
          users: d.users || 0,
          firstOccurrence: d.firstOccurrence
            ? new Date(d.firstOccurrence).toISOString()
            : null,
          lastOccurrence: d.lastOccurrence
            ? new Date(d.lastOccurrence).toISOString()
            : null,
          appVersions: d.appVersions || [],
          osVersions: d.osVersions || [],
          devices: d.devices || [],
        };
      });
    } catch {
      // crash_reports collection may not exist yet
    }

    const totalIssues = issues.length;
    const openIssues = issues.filter((i) => i.state === "open").length;
    const totalCrashes = issues.reduce((sum, i) => sum + (i.count || 0), 0);
    const affectedUsers = issues.reduce((sum, i) => sum + (i.users || 0), 0);

    // Crash-free rate is estimated from crash data (if no crashes, assume 100%)
    const crashFreeRate = totalCrashes === 0 ? 100 : Math.max(90, 100 - totalCrashes * 0.1);

    return NextResponse.json({
      issues,
      stats: {
        crashFreeRate: Math.round(crashFreeRate * 10) / 10,
        crashFreeRateDelta: 0,
        totalIssues,
        openIssues,
        totalCrashes,
        affectedUsers,
      },
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
