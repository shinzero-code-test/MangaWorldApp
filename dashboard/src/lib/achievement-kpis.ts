import { getAdminDb } from "./firebase-admin";
import type { QueryDocumentSnapshot } from "firebase-admin/firestore";

/**
 * Server-side recompute of the achievement KPIs (p3-next item 2.5 / U-31).
 *
 * ─── WHY THIS MODULE EXISTS ────────────────────────────────────────────────
 * The app writes its achievement state from the CLIENT in two places:
 *   • `user_achievements/{uid}`                (AchievementManager.syncToFirestore)
 *   • `users/{uid}/preferences/achievements`   (FirebaseSyncManager.pushLocalSnapshot)
 * and `firestore.rules` lets the OWNER write both (`allow read, write: if owner(uid)`).
 * The dashboard used to aggregate those client-written COUNTERS
 * (totalPagesRead / totalChaptersRead) and the client-computed unlock flags, so a
 * single document write let a user inflate their own KPIs (read/total counts
 * shown to admins).
 *
 * ─── THE RULE NOW ──────────────────────────────────────────────────────────
 * The dashboard never reports a client counter and never trusts a client unlock
 * flag. Every number is RECOMPUTED server-side from the app's own reading
 * evidence in the cloud:
 *
 *   `users/{uid}/preferences/reading_positions_{n}`
 *     → a map of reading positions, ONE ENTRY PER CHAPTER
 *        (`{mangaId}_{chapterNumber}` → {mangaId, chapterNumber, currentPage,
 *         totalPages, updatedAt}), written by ReadingPositionSyncManager.
 *
 * That is an EVENT-shaped source: one entry per chapter the user opened, with
 * the page they reached and the day they were there. Forging a chapter costs a
 * map entry (200 per document), not a counter — and the counters are gone from
 * the KPI path entirely.
 *
 * The client documents are still read for CONTENT only: the user's reading
 * GOALS (an intention, not a measurement). Their counters and unlock flags are
 * ignored, and the goals' PROGRESS is recomputed server-side from the same
 * evidence.
 *
 * ─── KNOWN LIMITATIONS (deliberate, documented) ────────────────────────────
 * • The goal ROWS themselves are client-authored (a user can add a goal). A
 *   goal is only reported when the server can corroborate activity inside the
 *   goal's own period window, so a goal row that nobody is reading towards
 *   never moves the `activeGoals` KPI.
 * • READING_TIME goals have no server-side source (reading duration lives in
 *   the local stats store), so their server progress is 0.
 * • Day buckets are UTC; the app counts local days. Offsets of a few hours can
 *   move a chapter from one day to the next near midnight.
 */

/** Reading positions live in `users/{uid}/preferences/reading_positions_{n}`. */
export const POSITION_CHUNK_PREFIX = "reading_positions_";

/** Documents per scan page. */
const PAGE_SIZE = 1000;

/** Hard cap on the scan (10 pages → 10k documents). The route is quota-guarded. */
const MAX_PAGES = 10;

const MS_PER_DAY = 86_400_000;

/** One reading position = one chapter the user opened. */
export interface PositionEntry {
  mangaId: string;
  chapterNumber: number;
  /** 1-based page the user reached in that chapter (the app stores `page + 1`). */
  currentPage: number;
  totalPages: number;
  /** ms epoch of the last time the position was touched. */
  updatedAt: number;
}

/** Server-recomputed reading facts for one user. */
export interface UserReadingStats {
  uid: string;
  /** Distinct chapters with a reading position. */
  chaptersRead: number;
  /** Pages the user actually reached (Σ currentPage over the chapters). */
  pagesRead: number;
  /** Distinct manga with a reading position. */
  mangaRead: number;
  /** Pages reached within the single most active day. */
  pagesOnBestDay: number;
  /** Distinct days with reading activity (UTC). */
  activeDays: number;
  /** Longest run of consecutive active days. */
  longestStreak: number;
  /** Most recent activity (ms epoch), 0 when unknown. */
  lastActivityAt: number;
  /** Pages reached per UTC day bucket. */
  pagesByDay: Map<number, number>;
  /** Chapters opened per UTC day bucket. */
  chaptersByDay: Map<number, number>;
}

/**
 * The server-side unlock rules — a mirror of the app's
 * `AchievementManager.checkAchievements`, evaluated against the RECOMPUTED
 * counters instead of the client's claims.
 *
 * The titles/descriptions mirror the app's `AchievementManager.getDefaultAchievements()`
 * so the dashboard does not have to trust the client payload for display text
 * (a forged document could otherwise rename an achievement card).
 */
export interface AchievementDefinition {
  id: string;
  title: string;
  description: string;
  unlocked: (stats: UserReadingStats) => boolean;
}

export const ACHIEVEMENT_CATALOG: AchievementDefinition[] = [
  { id: "first_chapter", title: "أول فصل", description: "اقرأ أول فصل", unlocked: (s) => s.chaptersRead >= 1 },
  { id: "bookworm", title: "دودة الكتب", description: "اقرأ 100 صفحة", unlocked: (s) => s.pagesRead >= 100 },
  { id: "speed_reader", title: "قارئ سريع", description: "اقرأ 50 صفحة في يوم واحد", unlocked: (s) => s.pagesOnBestDay >= 50 },
  { id: "streak_7", title: "سلسلة أسبوعية", description: "حافظ على سلسلة قراءة 7 أيام", unlocked: (s) => s.longestStreak >= 7 },
  { id: "streak_30", title: "سلسلة شهرية", description: "حافظ على سلسلة قراءة 30 يوم", unlocked: (s) => s.longestStreak >= 30 },
  { id: "manga_master", title: "أسطورة المانجا", description: "اقرأ 100 فصل", unlocked: (s) => s.chaptersRead >= 100 },
];

/** Goal types the app knows (GoalType). */
export type GoalType = "PAGES_READ" | "CHAPTERS_READ" | "READING_TIME";

/** Goal periods the app knows (GoalPeriod). */
export type GoalPeriod = "DAILY" | "WEEKLY" | "MONTHLY";

/** The raw goal row as the app writes it (client-authored). */
export interface ClientGoal {
  id: string;
  type: string;
  targetValue: number;
  currentValue: number;
  period: string;
  isActive: boolean;
  createdAt: number;
}

/** Longest run of consecutive day buckets. */
export function longestStreak(days: Iterable<number>): number {
  const sorted = [...new Set(days)].sort((a, b) => a - b);
  let best = 0;
  let run = 0;
  let previous: number | null = null;
  for (const day of sorted) {
    run = previous !== null && day === previous + 1 ? run + 1 : 1;
    previous = day;
    if (run > best) best = run;
  }
  return best;
}

/** Recompute one user's reading facts from their reading positions. */
export function computeUserStats(uid: string, entries: PositionEntry[]): UserReadingStats {
  const manga = new Set<string>();
  const days = new Set<number>();
  const pagesByDay = new Map<number, number>();
  const chaptersByDay = new Map<number, number>();
  let chaptersRead = 0;
  let pagesRead = 0;
  let lastActivityAt = 0;

  for (const entry of entries) {
    chaptersRead += 1;
    pagesRead += Math.max(0, entry.currentPage);
    if (entry.mangaId) manga.add(entry.mangaId);
    if (entry.updatedAt > 0) {
      const day = Math.floor(entry.updatedAt / MS_PER_DAY);
      days.add(day);
      pagesByDay.set(day, (pagesByDay.get(day) ?? 0) + Math.max(0, entry.currentPage));
      chaptersByDay.set(day, (chaptersByDay.get(day) ?? 0) + 1);
      if (entry.updatedAt > lastActivityAt) lastActivityAt = entry.updatedAt;
    }
  }

  let pagesOnBestDay = 0;
  for (const pages of pagesByDay.values()) if (pages > pagesOnBestDay) pagesOnBestDay = pages;

  return {
    uid,
    chaptersRead,
    pagesRead,
    mangaRead: manga.size,
    pagesOnBestDay,
    activeDays: days.size,
    longestStreak: longestStreak(days),
    lastActivityAt,
    pagesByDay,
    chaptersByDay,
  };
}

/** Number of days a goal period spans (rolling window, UTC). */
export function goalPeriodDays(period: string): number {
  switch (period) {
    case "WEEKLY":
      return 7;
    case "MONTHLY":
      return 30;
    case "DAILY":
    default:
      return 1;
  }
}

/**
 * The SERVER-verified progress for a goal, over the goal's own period window.
 * The client's `currentValue` is never used — a forged document could put
 * 999999 pages there.
 */
export function serverGoalProgress(
  stats: UserReadingStats,
  type: string,
  period: string,
  nowMs: number
): number {
  if (type === "READING_TIME") return 0; // no server-side reading-duration source
  const byDay = type === "CHAPTERS_READ" ? stats.chaptersByDay : stats.pagesByDay;
  const window = goalPeriodDays(period);
  const today = Math.floor(nowMs / MS_PER_DAY);
  let total = 0;
  for (let back = 0; back < window; back++) {
    total += byDay.get(today - back) ?? 0;
  }
  return total;
}

/** True when the server can see activity inside the goal's period window. */
export function hasPeriodActivity(
  stats: UserReadingStats,
  type: string,
  period: string,
  nowMs: number
): boolean {
  return serverGoalProgress(stats, type, period, nowMs) > 0;
}

/** Parse one reading-position entry out of a `reading_positions_*` map. */
export function toPositionEntry(value: unknown): PositionEntry | null {
  if (typeof value !== "object" || value === null) return null;
  const raw = value as Record<string, unknown>;
  const mangaId = typeof raw.mangaId === "string" ? raw.mangaId : "";
  if (!mangaId) return null;
  const chapterNumber = toFiniteNumber(raw.chapterNumber);
  if (chapterNumber === null) return null;
  const currentPage = toFiniteNumber(raw.currentPage) ?? 0;
  const totalPages = toFiniteNumber(raw.totalPages) ?? 0;
  const updatedAt = toFiniteNumber(raw.updatedAt) ?? 0;
  return { mangaId, chapterNumber, currentPage, totalPages, updatedAt };
}

function toFiniteNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return value;
}

/**
 * Load every user's reading facts from the cloud reading positions.
 *
 * One `collectionGroup("preferences")` scan (paged) instead of one query per
 * user: the dashboard's quota-burn guard allows 60 admin reads per minute, and
 * an N+1 scan would blow it.
 */
export async function loadUserReadingStats(): Promise<Map<string, UserReadingStats>> {
  const db = getAdminDb();
  const entriesByUid = new Map<string, PositionEntry[]>();
  // Dedupe across chunk documents: a chapter key must be counted once even if
  // it appears in two chunks (the chunk layout is stable, but a stale chunk
  // from an older layout can still hold a key).
  const seen = new Set<string>();
  let cursor: QueryDocumentSnapshot | undefined;

  for (let page = 0; page < MAX_PAGES; page++) {
    let query = db.collectionGroup("preferences").limit(PAGE_SIZE);
    if (cursor) query = query.startAfter(cursor);
    const snap = await query.get();
    if (snap.empty) break;

    for (const doc of snap.docs) {
      if (!doc.id.startsWith(POSITION_CHUNK_PREFIX)) continue;
      const uid = doc.ref.parent?.parent?.id;
      if (!uid) continue;
      const data = doc.data() ?? {};
      for (const value of Object.values(data)) {
        const entry = toPositionEntry(value);
        if (!entry) continue;
        const key = `${uid}|${entry.mangaId}_${entry.chapterNumber}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const list = entriesByUid.get(uid);
        if (list) list.push(entry);
        else entriesByUid.set(uid, [entry]);
      }
    }

    if (snap.size < PAGE_SIZE) break;
    cursor = snap.docs[snap.docs.length - 1];
  }

  const stats = new Map<string, UserReadingStats>();
  for (const [uid, entries] of entriesByUid) stats.set(uid, computeUserStats(uid, entries));
  return stats;
}
