import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { consumeRateLimit } from "@/lib/security";
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
    const admin = await requireRole("moderator");
    // Quota-burn guard: full Auth/fleet scans per request need a per-admin throttle.
    const rl = await consumeRateLimit("admin-read", admin.uid, 60, 60 * 1000);
    if (!rl.allowed) {
      return NextResponse.json({ error: "Too many requests" }, { status: 429 });
    }

    // DA-3: the app dual-writes achievements to TWO locations —
    // `user_achievements/{uid}` (AchievementManager.syncToFirestore) and
    // `users/{uid}/preferences/achievements` (FirebaseSyncManager push) —
    // with independent failure handling, so they can drift. The dashboard
    // used to read only the first and could under-report users whose latest
    // progress landed only in the second. Merge per uid (newer lastUpdated
    // wins totals, achievements/goals unioned) before aggregating.
    const snap = await getAdminDb().collection("user_achievements").limit(1000).get();
    // collectionGroup read is best-effort: index or permission hiccups must
    // not break the primary aggregation.
    let prefDocs: { id: string; data(): Record<string, unknown>; ref: { parent: { parent: { id: string } | null } } }[] = [];
    try {
      const prefSnap = await getAdminDb().collectionGroup("preferences").limit(1000).get();
      prefDocs = prefSnap.docs as unknown as typeof prefDocs;
    } catch {
      prefDocs = [];
    }

    interface MergedUser {
      totalPagesRead: number;
      totalChaptersRead: number;
      lastUpdated: number;
      achievements: Map<string, RawAchievement>;
      goals: Map<string, RawGoal>;
    }
    const byUid = new Map<string, MergedUser>();
    const ingest = (uid: string, data: Record<string, unknown>) => {
      let entry = byUid.get(uid);
      if (!entry) {
        entry = { totalPagesRead: 0, totalChaptersRead: 0, lastUpdated: 0, achievements: new Map(), goals: new Map() };
        byUid.set(uid, entry);
      }
      const updated = typeof data.lastUpdated === "number" ? data.lastUpdated : 0;
      // Totals are monotonic counters — take the max so a stale twin never
      // regresses the fleet aggregates.
      if (typeof data.totalPagesRead === "number") entry.totalPagesRead = Math.max(entry.totalPagesRead, data.totalPagesRead);
      if (typeof data.totalChaptersRead === "number") entry.totalChaptersRead = Math.max(entry.totalChaptersRead, data.totalChaptersRead);
      entry.lastUpdated = Math.max(entry.lastUpdated, updated);
      for (const a of parseJsonArray<RawAchievement>(data.achievements)) {
        if (typeof a.id !== "string" || !a.id) continue;
        const prev = entry.achievements.get(a.id);
        if (!prev) entry.achievements.set(a.id, a);
        else if (a.isUnlocked === true && prev.isUnlocked !== true) entry.achievements.set(a.id, a);
        else if (a.isUnlocked === true && typeof a.unlockedAt === "number" && (prev.unlockedAt ?? 0) < a.unlockedAt) {
          entry.achievements.set(a.id, a);
        }
      }
      for (const g of parseJsonArray<RawGoal>(data.goals)) {
        if (typeof g.id !== "string" || !g.id) continue;
        const prev = entry.goals.get(g.id);
        if (!prev) entry.goals.set(g.id, g);
        else if (typeof g.currentValue === "number" && (prev.currentValue ?? 0) < g.currentValue) {
          entry.goals.set(g.id, g);
        }
      }
    };
    for (const doc of snap.docs) ingest(doc.id, doc.data() as Record<string, unknown>);
    let fallbackCount = 0;
    for (const doc of prefDocs) {
      if (doc.id !== "achievements") continue;
      const uid = doc.ref.parent?.parent?.id;
      if (!uid) continue;
      ingest(uid, doc.data());
      fallbackCount += 1;
    }

    let totalPagesRead = 0;
    let totalChapters = 0;
    const byAchievement = new Map<string, {
      id: string; title: string; description: string;
      unlocked: number; total: number; unlockedAt?: number;
    }>();
    const goals: { id: string; label: string; current: number; target: number; unit: string }[] = [];

    for (const [uid, entry] of byUid) {
      totalPagesRead += entry.totalPagesRead;
      totalChapters += entry.totalChaptersRead;

      for (const a of entry.achievements.values()) {
        if (typeof a.id !== "string" || !a.id) continue;
        const agg = byAchievement.get(a.id) ?? {
          id: a.id,
          title: typeof a.title === "string" && a.title ? a.title : a.id,
          description: typeof a.description === "string" ? a.description : "",
          unlocked: 0, total: 0,
        };
        agg.total += 1;
        if (a.isUnlocked === true) {
          agg.unlocked += 1;
          if (typeof a.unlockedAt === "number" && (!agg.unlockedAt || a.unlockedAt > agg.unlockedAt)) {
            agg.unlockedAt = a.unlockedAt;
          }
        }
        byAchievement.set(a.id, agg);
      }

      for (const g of entry.goals.values()) {
        if (g.isActive !== true || typeof g.id !== "string" || !g.id) continue;
        if (goals.length >= 20) break;
        const meta = GOAL_LABELS[typeof g.type === "string" ? g.type : ""] ?? { label: String(g.type ?? "هدف"), unit: "" };
        goals.push({
          id: `${uid}:${g.id}`,
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
      // DA-3 observability: how many users needed the fallback twin.
      usersMerged: byUid.size,
      fallbackDocs: fallbackCount,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
