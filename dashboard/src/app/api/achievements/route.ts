import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

interface RawAchievement {
  id?: string;
  title?: string;
  description?: string;
  isUnlocked?: boolean;
  unlockedAt?: number;
}

interface RawGoal {
  id?: string;
  type?: string;
  targetValue?: number;
  currentValue?: number;
  period?: string;
  isActive?: boolean;
}

function parseJsonArray<T>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[];
  if (typeof raw !== "string" || !raw.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as T[]) : [];
  } catch {
    return [];
  }
}

function achievementType(id: string): string {
  const lower = id.toLowerCase();
  if (lower.includes("streak")) return "streak";
  if (lower.includes("speed")) return "speed";
  if (lower.includes("crown") || lower.includes("master")) return "crown";
  if (lower.includes("star")) return "star";
  if (lower.includes("chapter") || lower.includes("book") || lower.includes("worm") || lower.includes("read")) return "reading";
  return "award";
}

const GOAL_LABELS: Record<string, { label: string; unit: string }> = {
  PAGES_READ: { label: "صفحات مقروءة", unit: "صفحة" },
  CHAPTERS_READ: { label: "فصول مقروءة", unit: "فصل" },
  READING_TIME: { label: "وقت القراءة", unit: "دقيقة" },
  STREAK_DAYS: { label: "أيام متتالية", unit: "يوم" },
};

export async function GET() {
  try {
    await requireRole("moderator");

    // The app syncs per-user progress into `user_achievements/{uid}` (totals
    // plus JSON-encoded achievements[]/goals[]). There is no global
    // achievements/goals/appStats collection — aggregate the fleet here.
    const snap = await getAdminDb().collection("user_achievements").limit(1000).get();

    let totalPagesRead = 0;
    let totalChapters = 0;
    const byAchievement = new Map<string, {
      id: string; title: string; description: string;
      unlocked: number; total: number; unlockedAt?: number;
    }>();
    const goals: { id: string; label: string; current: number; target: number; unit: string }[] = [];

    for (const doc of snap.docs) {
      const data = doc.data();
      totalPagesRead += typeof data.totalPagesRead === "number" ? data.totalPagesRead : 0;
      totalChapters += typeof data.totalChaptersRead === "number" ? data.totalChaptersRead : 0;

      for (const a of parseJsonArray<RawAchievement>(data.achievements)) {
        if (typeof a.id !== "string" || !a.id) continue;
        const entry = byAchievement.get(a.id) ?? {
          id: a.id,
          title: typeof a.title === "string" && a.title ? a.title : a.id,
          description: typeof a.description === "string" ? a.description : "",
          unlocked: 0, total: 0,
        };
        entry.total += 1;
        if (a.isUnlocked === true) {
          entry.unlocked += 1;
          if (typeof a.unlockedAt === "number" && (!entry.unlockedAt || a.unlockedAt > entry.unlockedAt)) {
            entry.unlockedAt = a.unlockedAt;
          }
        }
        byAchievement.set(a.id, entry);
      }

      for (const g of parseJsonArray<RawGoal>(data.goals)) {
        if (g.isActive !== true || typeof g.id !== "string" || !g.id) continue;
        if (goals.length >= 20) break;
        const meta = GOAL_LABELS[typeof g.type === "string" ? g.type : ""] ?? { label: String(g.type ?? "هدف"), unit: "" };
        goals.push({
          id: `${doc.id}:${g.id}`,
          label: meta.label,
          current: typeof g.currentValue === "number" ? g.currentValue : 0,
          target: typeof g.targetValue === "number" && g.targetValue > 0 ? g.targetValue : 0,
          unit: meta.unit,
        });
      }
    }

    const achievements = [...byAchievement.values()].map((e) => ({
      id: e.id,
      title: e.title,
      description: e.description,
      type: achievementType(e.id),
      isUnlocked: e.unlocked > 0,
      unlockedAt: e.unlockedAt,
      count: e.unlocked,
    }));

    return NextResponse.json({
      totalPagesRead,
      totalChapters,
      unlockedAchievements: achievements.filter((a) => a.isUnlocked).length,
      activeGoals: goals.length,
      achievements,
      goals,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
