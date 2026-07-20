import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAccessToken } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID ?? "";

export async function GET() {
  try {
    await requireRole("super-admin");

    const token = await getAccessToken();
    const baseUrl = `https://fabriccrashlytics.googleapis.com/v1beta1/projects/${PROJECT_ID}/issues`;

    // Fetch crash issues from Crashlytics REST API
    let issues: any[] = [];
    try {
      const response = await fetch(`${baseUrl}?pageSize=50&state=OPEN`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (response.ok) {
        const data = await response.json();
        issues = (data.issues || []).map((issue: any) => ({
          id: issue.issueId || issue.id || "",
          title: issue.title || "Unknown crash",
          subtitle: issue.subtitle || "",
          state: issue.state?.toLowerCase() || "open",
          count: issue.issueCount || 0,
          users: issue.affectedUserCount || 0,
          firstOccurrence: issue.firstOccurrence?.substring(0, 19) || null,
          lastOccurrence: issue.latestOccurrence?.substring(0, 19) || null,
          appVersions: issue.appVersionDisplay || [],
          osVersions: issue.osVersionDisplay || [],
          devices: issue.deviceModelDisplay || [],
        }));
      }
    } catch {
      // Crashlytics API may not be available
    }

    const totalIssues = issues.length;
    const openIssues = issues.filter((i) => i.state === "open").length;
    const totalCrashes = issues.reduce((sum, i) => sum + (i.count || 0), 0);
    const affectedUsers = issues.reduce((sum, i) => sum + (i.users || 0), 0);
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
