import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { consumeRateLimit } from "@/lib/security";
import { reqUrl, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/analytics/engagement (the daily-active / top-manga
// / reading-hour distribution read-out).
//
// Pinned contracts:
//  - moderator gate (401/403) and the per-admin quota-burn guard (429)
//  - the `days` window is CLAMPED to [1, 31] — each day costs sequential
//    count queries, so an unbounded window is a Firestore-cost DoS
//  - the reading-hour distribution is derived from the achievement
//    timestamps and always sums to 100%
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

describe("GET /api/analytics/engagement", () => {
  it("returns the daily series, the top manga and the reading distribution", async () => {
    fake.setCount("publicProfiles", 5);
    fake.put("community_manga", "m1", { title: "Popular" });
    fake.setCount("community_manga/m1/comments", 4);
    fake.setCount("community_manga/m1/reviews", 1);
    fake.put("user_achievements", "uid-1", { lastUpdated: Date.now() });

    const res = await GET(reqUrl("http://localhost/api/analytics/engagement?days=3"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.dailyActive).toHaveLength(3);
    expect(body.topManga[0]).toEqual({ name: "Popular", reads: 5 });
    const pctSum = body.readingDistribution.reduce((s: number, d: { pct: number }) => s + d.pct, 0);
    expect(pctSum).toBe(100);
  });

  it("clamps the days window to [1, 31]", async () => {
    const huge = await GET(reqUrl("http://localhost/api/analytics/engagement?days=9999"));
    expect((await huge.json()).dailyActive).toHaveLength(31);

    // A non-numeric (or zero) window falls back to the 7-day default.
    const zero = await GET(reqUrl("http://localhost/api/analytics/engagement?days=0"));
    expect((await zero.json()).dailyActive).toHaveLength(7);
  });

  it("returns 429 when the admin-read quota is exhausted", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    expect((await GET(reqUrl("http://localhost/api/analytics/engagement"))).status).toBe(429);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/analytics/engagement"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/analytics/engagement"))).status).toBe(403);
  });
});
