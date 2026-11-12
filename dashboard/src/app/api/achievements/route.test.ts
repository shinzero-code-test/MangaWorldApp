import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { consumeRateLimit } from "@/lib/security";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/achievements (the fleet achievement aggregates).
//
// Pinned contracts:
//  - moderator gate (401/403) and the per-admin quota-burn guard (429)
//  - p3-next 2.5 / U-31: every NUMBER is recomputed SERVER-SIDE from the
//    app's cloud reading evidence (`users/{uid}/preferences/reading_positions_*`,
//    one entry per chapter). The client-written `user_achievements/{uid}` and
//    `users/{uid}/preferences/achievements` documents are read for CONTENT
//    only (the reading goals); their counters and unlock flags are ignored,
//    because `firestore.rules` lets the owner write both.
//  - the goals' PROGRESS is server-verified too (the client's currentValue is
//    never used) and a goal nobody reads towards is not reported at all.
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return { ...actual, consumeRateLimit: vi.fn().mockResolvedValue({ allowed: true }) };
});

const DAY_MS = 86_400_000;

let fake = createFakeDb();

/** Seed a user's cloud reading positions (one entry per chapter). */
function seedPositions(
  uid: string,
  entries: Array<{ mangaId: string; chapter: number; page: number; updatedAt: number }>
) {
  const map: Record<string, unknown> = {};
  for (const entry of entries) {
    map[`${entry.mangaId}_${entry.chapter}`] = {
      mangaId: entry.mangaId,
      chapterNumber: entry.chapter,
      currentPage: entry.page,
      totalPages: entry.page + 5,
      updatedAt: entry.updatedAt,
    };
  }
  fake.putGroupAt("preferences", "reading_positions_0", map, `users/${uid}/preferences/reading_positions_0`);
}

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("GET /api/achievements", () => {
  it("recomputes the fleet KPI from the server-side reading evidence", async () => {
    const today = Date.now();
    seedPositions("uid-1", [
      { mangaId: "m1", chapter: 1, page: 10, updatedAt: today },
      { mangaId: "m1", chapter: 2, page: 20, updatedAt: today },
      { mangaId: "m2", chapter: 1, page: 30, updatedAt: today - 3 * DAY_MS },
    ]);
    // The client document claims something else entirely — it must not count.
    fake.put("user_achievements", "uid-1", {
      totalPagesRead: 999_999,
      totalChaptersRead: 999_999,
      lastUpdated: 5,
      achievements: JSON.stringify([
        { id: "first_chapter", title: "x", isUnlocked: true },
        { id: "bookworm", title: "y", isUnlocked: true },
        { id: "manga_master", title: "z", isUnlocked: true },
      ]),
      goals: "[]",
    });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    // 3 chapters, 10 + 20 + 30 pages.
    expect(body.totalChapters).toBe(3);
    expect(body.totalPagesRead).toBe(60);
    // The client's claimed unlocks are ignored: 60 pages < 100 → no bookworm.
    const byId = Object.fromEntries(body.achievements.map((a: { id: string }) => [a.id, a]));
    expect(byId.first_chapter.count).toBe(1);
    expect(byId.first_chapter.isUnlocked).toBe(true);
    expect(byId.bookworm.count).toBe(0);
    expect(byId.bookworm.isUnlocked).toBe(false);
    expect(byId.manga_master.count).toBe(0);
    expect(body.unlockedAchievements).toBe(1);
    expect(body.verifiedUsers).toBe(1);
  });

  it("derives the streak and the speed achievements from the evidence", async () => {
    const today = Date.now();
    // 8 consecutive days of activity, 60 pages on the first day.
    const entries = [];
    for (let day = 0; day < 8; day++) {
      entries.push({
        mangaId: `m${day}`,
        chapter: 1,
        page: day === 0 ? 60 : 5,
        updatedAt: today - day * DAY_MS,
      });
    }
    seedPositions("uid-1", entries);

    const res = await GET();
    const body = await res.json();
    const byId = Object.fromEntries(body.achievements.map((a: { id: string }) => [a.id, a]));
    // 8 consecutive activity days → the 7-day streak is unlocked.
    expect(byId.streak_7.count).toBe(1);
    expect(byId.streak_30.count).toBe(0);
    // 60 pages on a single day → the speed reader is unlocked.
    expect(byId.speed_reader.count).toBe(1);
  });

  it("reports a goal with the server-verified progress and ignores the client's value", async () => {
    const today = Date.now();
    seedPositions("uid-1", [{ mangaId: "m1", chapter: 1, page: 42, updatedAt: today }]);
    fake.put("user_achievements", "uid-1", {
      totalPagesRead: 42,
      totalChaptersRead: 1,
      lastUpdated: 9,
      achievements: "[]",
      goals: JSON.stringify([
        { id: "goal_1", type: "PAGES_READ", targetValue: 100, currentValue: 999_999, period: "DAILY", isActive: true },
      ]),
    });

    const res = await GET();
    const body = await res.json();
    expect(body.activeGoals).toBe(1);
    expect(body.goals[0]).toMatchObject({ id: "uid-1:goal_1", current: 42, target: 100, unit: "صفحة" });
    // The forged client progress never surfaces.
    expect(body.goals[0].current).not.toBe(999_999);
  });

  it("does not report a goal the server cannot see any reading for", async () => {
    const today = Date.now();
    // The reader's only activity is 40 days old — a DAILY goal has no
    // server-verified progress in its window, so the goal is not reported.
    seedPositions("uid-1", [{ mangaId: "m1", chapter: 1, page: 42, updatedAt: today - 40 * DAY_MS }]);
    fake.put("user_achievements", "uid-1", {
      totalPagesRead: 42,
      totalChaptersRead: 1,
      lastUpdated: 9,
      achievements: "[]",
      goals: JSON.stringify([
        { id: "goal_1", type: "PAGES_READ", targetValue: 100, currentValue: 999_999, period: "DAILY", isActive: true },
      ]),
    });

    const res = await GET();
    const body = await res.json();
    expect(body.activeGoals).toBe(0);
    expect(body.goals).toEqual([]);
  });

  it("a forged achievement doc does not change the aggregate", async () => {
    const today = Date.now();
    seedPositions("uid-1", [
      { mangaId: "m1", chapter: 1, page: 10, updatedAt: today },
      { mangaId: "m1", chapter: 2, page: 20, updatedAt: today },
    ]);
    const honestDoc = {
      totalPagesRead: 30,
      totalChaptersRead: 2,
      lastUpdated: 5,
      achievements: JSON.stringify([{ id: "first_chapter", title: "x", isUnlocked: true }]),
      goals: JSON.stringify([
        { id: "goal_1", type: "PAGES_READ", targetValue: 100, currentValue: 30, period: "DAILY", isActive: true },
      ]),
    };
    fake.put("user_achievements", "uid-1", honestDoc);

    const baseline = await (await GET()).json();

    // The owner rewrites their own document: inflated counters, every
    // achievement claimed as unlocked and a fabricated goal progress.
    fake.put("user_achievements", "uid-1", {
      ...honestDoc,
      totalPagesRead: 999_999_999,
      totalChaptersRead: 999_999_999,
      achievements: JSON.stringify([
        { id: "first_chapter", title: "x", isUnlocked: true },
        { id: "bookworm", title: "y", isUnlocked: true },
        { id: "speed_reader", title: "z", isUnlocked: true },
        { id: "streak_7", title: "w", isUnlocked: true },
        { id: "streak_30", title: "v", isUnlocked: true },
        { id: "manga_master", title: "u", isUnlocked: true },
      ]),
      goals: JSON.stringify([
        { id: "goal_1", type: "PAGES_READ", targetValue: 100, currentValue: 999_999, period: "DAILY", isActive: true },
      ]),
    });

    const forged = await (await GET()).json();

    // The read KPIs are untouched by the forgery.
    expect(forged.totalPagesRead).toBe(baseline.totalPagesRead);
    expect(forged.totalChapters).toBe(baseline.totalChapters);
    // The unlock KPIs are untouched by the forgery.
    expect(forged.unlockedAchievements).toBe(baseline.unlockedAchievements);
    expect(forged.achievements.map((a: { count: number }) => a.count)).toEqual(
      baseline.achievements.map((a: { count: number }) => a.count),
    );
    // The goal progress is the server-verified one, not the forged value.
    expect(forged.goals[0].current).toBe(baseline.goals[0].current);
    expect(forged.goals[0].current).toBe(30);
  });

  it("a forged document for a user without reading evidence changes nothing", async () => {
    const today = Date.now();
    seedPositions("uid-1", [{ mangaId: "m1", chapter: 1, page: 10, updatedAt: today }]);
    fake.put("user_achievements", "uid-1", { totalPagesRead: 10, totalChaptersRead: 1, lastUpdated: 5, achievements: "[]", goals: "[]" });

    const baseline = await (await GET()).json();

    fake.put("user_achievements", "uid-2", {
      totalPagesRead: 999_999_999,
      totalChaptersRead: 999_999_999,
      lastUpdated: 9,
      achievements: JSON.stringify([{ id: "manga_master", title: "x", isUnlocked: true }]),
      goals: JSON.stringify([
        { id: "goal_9", type: "PAGES_READ", targetValue: 100, currentValue: 999_999, period: "DAILY", isActive: true },
      ]),
    });

    const forged = await (await GET()).json();
    expect(forged.totalPagesRead).toBe(baseline.totalPagesRead);
    expect(forged.totalChapters).toBe(baseline.totalChapters);
    expect(forged.unlockedAchievements).toBe(baseline.unlockedAchievements);
    expect(forged.activeGoals).toBe(baseline.activeGoals);
    expect(forged.goals).toEqual(baseline.goals);
    expect(forged.achievements.map((a: { count: number }) => a.count)).toEqual(
      baseline.achievements.map((a: { count: number }) => a.count),
    );
  });

  it("merges the preferences twin without changing the server numbers (DA-3)", async () => {
    const today = Date.now();
    seedPositions("uid-1", [{ mangaId: "m1", chapter: 1, page: 30, updatedAt: today }]);
    fake.put("user_achievements", "uid-1", { totalPagesRead: 100, totalChaptersRead: 10, lastUpdated: 5, achievements: "[]", goals: "[]" });
    // The twin carries NEWER claimed progress — it must not move the KPI.
    fake.putGroupAt(
      "preferences",
      "achievements",
      { totalPagesRead: 250, totalChaptersRead: 12, lastUpdated: 9, achievements: "[]", goals: "[]" },
      "users/uid-1/preferences/achievements",
    );

    const res = await GET();
    const body = await res.json();
    expect(body.totalPagesRead).toBe(30);
    expect(body.totalChapters).toBe(1);
    expect(body.verifiedUsers).toBe(1);
    expect(body.clientDocUsers).toBe(1);
  });

  it("returns 429 when the admin-read quota is exhausted", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    expect((await GET()).status).toBe(429);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
