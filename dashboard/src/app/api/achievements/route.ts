import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("moderator");

    try {
      const [achSnap, goalsSnap, statsSnap] = await Promise.all([
        getAdminDb().collection("achievements").limit(50).get(),
        getAdminDb().collection("goals").where("active", "==", true).limit(20).get(),
        getAdminDb().collection("appStats").doc("global").get(),
      ]);

      const achievements = achSnap.docs.map((d) => ({ id: d.id, ...d.data() }));
      const goals        = goalsSnap.docs.map((d) => ({ id: d.id, ...d.data() }));
      const stats        = statsSnap.data() ?? {};

      return NextResponse.json({
        totalPagesRead:       stats.totalPagesRead       ?? 0,
        totalChapters:        stats.totalChapters        ?? 0,
        unlockedAchievements: achievements.filter((a: any) => a.isUnlocked).length,
        activeGoals:          goals.length,
        achievements,
        goals,
      });
    } catch {
      // Return empty data on failure
      return NextResponse.json({
        totalPagesRead:       0,
        totalChapters:        0,
        unlockedAchievements: 0,
        activeGoals:          0,
        achievements:         [],
        goals:                [],
      });
    }
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
