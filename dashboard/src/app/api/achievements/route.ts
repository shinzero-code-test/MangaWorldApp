import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { consumeRateLimit } from "@/lib/security";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";
import {
  ACHIEVEMENT_CATALOG,
  type ClientGoal,
  hasPeriodActivity,
  loadUserReadingStats,
  serverGoalProgress,
} from "@/lib/achievement-kpis";

export const dynamic = 'force-dynamic';

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

/** Max goal rows reported (the fleet table only renders the first ones). */
const MAX_GOALS = 20;

/**
 * The fleet achievement aggregate.
 *
 * ─── SERVER-RECOMPUTED (p3-next 2.5 / U-31) ────────────────────────────────
 * Every NUMBER in this response is recomputed server-side from the app's own
 * reading evidence in the cloud (`users/{uid}/preferences/reading_positions_*`,
 * one entry per chapter). The client-written `user_achievements/{uid}` and
 * `users/{uid}/preferences/achievements` documents are read for CONTENT only —
 * the user's reading GOALS — and their counters and unlock flags are ignored,
 * because `firestore.rules` lets the owner write both documents and a single
 * write used to inflate the fleet KPIs.
 *
 * See `lib/achievement-kpis.ts` for the evidence model and its limits.
 */
export async function GET() {
  try {
    const admin = await requireRole("moderator");
    // Quota-burn guard: full Auth/fleet scans per request need a per-admin throttle.
    const rl = await consumeRateLimit("admin-read", admin.uid, 60, 60 * 1000);
    if (!rl.allowed) {
      return NextResponse.json({ error: "Too many requests" }, { status: 429 });
    }

    const now = Date.now();

    // ── 1. The authoritative source: the server-visible reading evidence.
    const stats = await loadUserReadingStats();

    // ── 2. Client documents: CONTENT ONLY (the reading goals).
    //    Their counters (totalPagesRead/totalChaptersRead) and their unlock
    //    flags are deliberately NOT read — see the module header.
    const clientDocs = await getAdminDb().collection("user_achievements").limit(1000).get();
    let prefDocs: { id: string; data(): Record<string, unknown>; ref: { parent: { parent: { id: string } | null } } }[] = [];
    try {
      const prefSnap = await getAdminDb().collectionGroup("preferences").limit(1000).get();
      prefDocs = prefSnap.docs as unknown as typeof prefDocs;
    } catch {
      prefDocs = [];
    }

    /** uid → the goals that user declared (client-authored intentions). */
    const clientGoals = new Map<string, Map<string, ClientGoal>>();
    const ingestGoals = (uid: string, data: Record<string, unknown>) => {
      let goals = clientGoals.get(uid);
      if (!goals) {
        goals = new Map<string, ClientGoal>();
        clientGoals.set(uid, goals);
      }
      for (const raw of parseJsonArray<Record<string, unknown>>(data.goals)) {
        const id = typeof raw.id === "string" ? raw.id : "";
        if (!id) continue;
        const createdAt = typeof raw.createdAt === "number" ? raw.createdAt : 0;
        const existing = goals.get(id);
        // DA-3: the twins can drift — keep the newer declaration of a goal.
        if (existing && existing.createdAt >= createdAt) continue;
        goals.set(id, {
          id,
          type: typeof raw.type === "string" ? raw.type : "",
          targetValue: typeof raw.targetValue === "number" ? raw.targetValue : 0,
          currentValue: typeof raw.currentValue === "number" ? raw.currentValue : 0,
          period: typeof raw.period === "string" ? raw.period : "DAILY",
          isActive: raw.isActive !== false,
          createdAt,
        });
      }
    };
    for (const doc of clientDocs.docs) ingestGoals(doc.id, doc.data() as Record<string, unknown>);
    for (const doc of prefDocs) {
      if (doc.id !== "achievements") continue;
      const uid = doc.ref.parent?.parent?.id;
      if (!uid) continue;
      ingestGoals(uid, doc.data());
    }

    // ── 3. Fleet KPIs — recomputed from the evidence.
    let totalPagesRead = 0;
    let totalChapters = 0;
    const byAchievement = new Map<string, {
      unlocked: number;
      unlockedAt?: number;
    }>();
    for (const definition of ACHIEVEMENT_CATALOG) {
      byAchievement.set(definition.id, { unlocked: 0 });
    }

    for (const userStats of stats.values()) {
      totalPagesRead += userStats.pagesRead;
      totalChapters += userStats.chaptersRead;
      for (const definition of ACHIEVEMENT_CATALOG) {
        const aggregate = byAchievement.get(definition.id);
        if (!aggregate) continue;
        if (!definition.unlocked(userStats)) continue;
        aggregate.unlocked += 1;
        if (userStats.lastActivityAt > 0 && userStats.lastActivityAt > (aggregate.unlockedAt ?? 0)) {
          aggregate.unlockedAt = userStats.lastActivityAt;
        }
      }
    }

    // ── 4. Goals: client-authored INTENTIONS, server-verified PROGRESS.
    //    A goal is reported only when the server can see the user reading
    //    inside the goal's own period window, so a goal row that nobody reads
    //    towards never moves the `activeGoals` KPI.
    const goals: { id: string; label: string; current: number; target: number; unit: string }[] = [];
    for (const [uid, declared] of clientGoals) {
      if (goals.length >= MAX_GOALS) break;
      const userStats = stats.get(uid);
      if (!userStats) continue; // no server evidence → the goal is not reported
      for (const goal of declared.values()) {
        if (goals.length >= MAX_GOALS) break;
        if (!hasPeriodActivity(userStats, goal.type, goal.period, now)) continue;
        const meta = GOAL_LABELS[goal.type] ?? { label: String(goal.type ?? "هدف"), unit: "" };
        goals.push({
          id: `${uid}:${goal.id}`,
          label: meta.label,
          // SERVER progress — the client's `currentValue` is never used.
          current: serverGoalProgress(userStats, goal.type, goal.period, now),
          target: goal.targetValue > 0 ? goal.targetValue : 0,
          unit: meta.unit,
        });
      }
    }

    const achievements = ACHIEVEMENT_CATALOG.map((definition) => {
      const aggregate = byAchievement.get(definition.id) ?? { unlocked: 0 };
      return {
        id: definition.id,
        title: definition.title,
        description: definition.description,
        type: achievementType(definition.id),
        isUnlocked: aggregate.unlocked > 0,
        unlockedAt: aggregate.unlockedAt,
        count: aggregate.unlocked,
      };
    });

    return NextResponse.json({
      totalPagesRead,
      totalChapters,
      unlockedAchievements: achievements.filter((a) => a.isUnlocked).length,
      activeGoals: goals.length,
      achievements,
      goals,
      // Coverage diagnostics: how much of the fleet the server can verify, and
      // how many users still only have a client-written achievement document.
      verifiedUsers: stats.size,
      clientDocUsers: clientGoals.size,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
