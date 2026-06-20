import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export async function GET(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { searchParams } = new URL(request.url);
    const appId = searchParams.get("appId") || "1:585544727612:android:com.exapps.mangaworld";

    // Firebase Crashlytics API requires special setup
    // For now, return structure that the frontend can display
    // In production, this would use the Firebase Admin SDK or REST API

    const issues = [
      {
        id: "crash_001",
        title: "NullPointerException in ReaderScreen",
        subtitle: "com.exapps.mangaworld.presentation.reader.ReaderScreen",
        state: "open",
        count: 23,
        users: 12,
        firstOccurrence: new Date(Date.now() - 86400000 * 3).toISOString(),
        lastOccurrence: new Date(Date.now() - 3600000).toISOString(),
        appVersions: ["3.16.0", "3.17.0"],
        osVersions: ["Android 14", "Android 13"],
        devices: ["Samsung Galaxy S24", "Pixel 8"],
      },
      {
        id: "crash_002",
        title: "OutOfMemoryError in ImageLoader",
        subtitle: "coil.ImageLoader.execute",
        state: "open",
        count: 8,
        users: 5,
        firstOccurrence: new Date(Date.now() - 86400000 * 7).toISOString(),
        lastOccurrence: new Date(Date.now() - 86400000).toISOString(),
        appVersions: ["3.17.0"],
        osVersions: ["Android 12"],
        devices: ["Xiaomi Redmi Note 11"],
      },
      {
        id: "crash_003",
        title: "NetworkOnMainThreadException",
        subtitle: "com.exapps.mangaworld.core.data.remote.scraper.BaseScraper",
        state: "resolved",
        count: 45,
        users: 30,
        firstOccurrence: new Date(Date.now() - 86400000 * 14).toISOString(),
        lastOccurrence: new Date(Date.now() - 86400000 * 2).toISOString(),
        appVersions: ["3.15.0", "3.16.0"],
        osVersions: ["Android 14", "Android 13", "Android 12"],
        devices: ["Various"],
      },
    ];

    const stats = {
      crashFreeRate: 99.2,
      crashFreeRateDelta: 0.3,
      totalIssues: issues.length,
      openIssues: issues.filter(i => i.state === "open").length,
      totalCrashes: issues.reduce((sum, i) => sum + i.count, 0),
      affectedUsers: issues.reduce((sum, i) => sum + i.users, 0),
    };

    return NextResponse.json({ issues, stats });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
