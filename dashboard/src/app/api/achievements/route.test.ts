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
//  - DA-3: the app dual-writes achievements to `user_achievements/{uid}` AND
//    `users/{uid}/preferences/achievements`. The route MERGES both per uid:
//    the totals are monotonic (max, never a regression) and the achievement
//    / goal sets are unioned — a user whose latest progress landed only in
//    the second twin must still be counted.
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

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("GET /api/achievements", () => {
  it("aggregates the primary achievement store", async () => {
    fake.put("user_achievements", "uid-1", {
      totalPagesRead: 100,
      totalChaptersRead: 10,
      lastUpdated: 5,
      achievements: JSON.stringify([
        { id: "first_chapter", title: "First", isUnlocked: true, unlockedAt: 5 },
      ]),
      goals: JSON.stringify([
        { id: "g1", type: "PAGES_READ", currentValue: 40, targetValue: 100, isActive: true },
      ]),
    });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.totalPagesRead).toBe(100);
    expect(body.totalChapters).toBe(10);
    expect(body.unlockedAchievements).toBe(1);
    expect(body.achievements[0]).toMatchObject({ id: "first_chapter", count: 1, isUnlocked: true });
    expect(body.goals[0]).toMatchObject({ id: "uid-1:g1", current: 40, target: 100, unit: "صفحة" });
  });

  it("merges the preferences twin without regressing the totals (DA-3)", async () => {
    fake.put("user_achievements", "uid-1", {
      totalPagesRead: 100,
      totalChaptersRead: 10,
      lastUpdated: 5,
      achievements: JSON.stringify([{ id: "first_chapter", isUnlocked: true, unlockedAt: 5 }]),
    });
    // The twin has NEWER progress and a second achievement.
    fake.putGroupAt(
      "preferences",
      "achievements",
      {
        totalPagesRead: 250,
        totalChaptersRead: 12,
        lastUpdated: 9,
        achievements: JSON.stringify([
          { id: "first_chapter", isUnlocked: true, unlockedAt: 5 },
          { id: "streak_7", title: "Streak", isUnlocked: true, unlockedAt: 9 },
        ]),
      },
      "users/uid-1/preferences/achievements",
    );

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    // The monotonic counter takes the MAX of the two twins.
    expect(body.totalPagesRead).toBe(250);
    expect(body.totalChapters).toBe(12);
    // One user, two achievements (the union, not a double count).
    expect(body.usersMerged).toBe(1);
    expect(body.fallbackDocs).toBe(1);
    expect(body.unlockedAchievements).toBe(2);
  });

  it("never regresses the totals when the twin is stale", async () => {
    fake.put("user_achievements", "uid-1", { totalPagesRead: 100, totalChaptersRead: 10, lastUpdated: 9 });
    fake.putGroupAt(
      "preferences",
      "achievements",
      { totalPagesRead: 3, totalChaptersRead: 1, lastUpdated: 1 },
      "users/uid-1/preferences/achievements",
    );

    const res = await GET();
    const body = await res.json();
    expect(body.totalPagesRead).toBe(100);
    expect(body.totalChapters).toBe(10);
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
