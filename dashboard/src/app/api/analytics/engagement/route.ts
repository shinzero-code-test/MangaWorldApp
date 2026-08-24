import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    // Moderator minimum: "viewer" rank would admit the viewer role itself (M-4).
    await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const days = parseInt(searchParams.get("days") || "7", 10);
    const db = getAdminDb();

    // Daily active users (from publicProfiles.updatedAt)
    const dailyActive = [];
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date();
      d.setDate(d.getDate() - i);
      d.setHours(0, 0, 0, 0);
      const start = d.getTime();
      const end = start + 86400000;
      try {
        const snap = await db.collection("publicProfiles")
          .where("updatedAt", ">=", start).where("updatedAt", "<", end).count().get();
        dailyActive.push({
          date: d.toLocaleDateString("ar-EG", { weekday: "short" }),
          users: snap.data().count,
        });
      } catch {
        dailyActive.push({
          date: d.toLocaleDateString("ar-EG", { weekday: "short" }),
          users: 0,
        });
      }
    }

    // Top manga from community_manga comments/reviews
    const topManga: { name: string; reads: number }[] = [];
    try {
      const mangasSnap = await db.collection("community_manga").limit(200).get();
      const mangaCounts: Record<string, number> = {};
      for (const doc of mangasSnap.docs) {
        const title = doc.data().title || "Unknown";
        const commentsSnap = await doc.ref.collection("comments").count().get();
        const reviewsSnap = await doc.ref.collection("reviews").count().get();
        mangaCounts[title] = (commentsSnap.data().count || 0) + (reviewsSnap.data().count || 0);
      }
      Object.entries(mangaCounts)
        .sort(([, a], [, b]) => b - a)
        .slice(0, 5)
        .forEach(([name, reads]) => topManga.push({ name, reads }));
    } catch {
      // Collection may not exist
    }

    // Reading time distribution (estimate from user_achievements)
    const readingDistribution = [
      { time: "6 صباحاً - 12 ظهراً", pct: 0 },
      { time: "12 ظهراً - 6 مساءً", pct: 0 },
      { time: "6 مساءً - 12 ليلاً", pct: 0 },
      { time: "12 ليلاً - 6 صباحاً", pct: 0 },
    ];
    try {
      const achieveSnap = await db.collection("user_achievements").limit(100).get();
      let morning = 0, afternoon = 0, evening = 0, night = 0;
      for (const doc of achieveSnap.docs) {
        const updated = doc.data().lastUpdated || 0;
        const hour = new Date(updated).getHours();
        if (hour >= 6 && hour < 12) morning++;
        else if (hour >= 12 && hour < 18) afternoon++;
        else if (hour >= 18 && hour < 24) evening++;
        else night++;
      }
      const total = morning + afternoon + evening + night || 1;
      readingDistribution[0].pct = Math.round((morning / total) * 100);
      readingDistribution[1].pct = Math.round((afternoon / total) * 100);
      readingDistribution[2].pct = Math.round((evening / total) * 100);
      readingDistribution[3].pct = Math.round((night / total) * 100);
    } catch {
      // No achievement data available
    }

    return NextResponse.json({
      dailyActive,
      topManga,
      readingDistribution,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
