import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { getDashboardRoleCounts } from "@/lib/auth";
import { consumeRateLimit } from "@/lib/security";
import { reqUrl, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/analytics/summary (the fleet overview).
//
// Pinned contracts:
//  - moderator gate (401/403) and the per-admin quota-burn guard (429)
//  - the period window is CLAMPED (7d/30d/90d) — the daily-active series
//    costs one count query per day, so an unbounded window is a DoS
//  - the overview counts come from Firestore and the role distribution
//    from the Auth fleet
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn(), getDashboardRoleCounts: vi.fn() };
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
  vi.mocked(getDashboardRoleCounts).mockResolvedValue({ viewer: 1, moderator: 2, "super-admin": 1 });
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("GET /api/analytics/summary", () => {
  it("returns the overview and the engagement series", async () => {
    fake.setCount("publicProfiles", 20);
    fake.setCount("moderationReports", 3);
    // `lists` is read as a collectionGroup → the group store, not a path.
    for (let i = 0; i < 6; i++) fake.putGroup("lists", `l${i}`, { title: `list ${i}` });

    const res = await GET(reqUrl("http://localhost/api/analytics/summary?period=7d"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.overview.totalUsers).toBe(20);
    expect(body.overview.openReports).toBe(3);
    expect(body.overview.totalLists).toBe(6);
    expect(body.overview.roleDistribution).toEqual({ viewer: 1, moderator: 2, "super-admin": 1 });
    expect(body.engagement.dailyActive).toHaveLength(7);
  });

  it("clamps the period to the supported windows", async () => {
    const ninety = await GET(reqUrl("http://localhost/api/analytics/summary?period=90d"));
    expect((await ninety.json()).engagement.dailyActive).toHaveLength(90);

    const unknown = await GET(reqUrl("http://localhost/api/analytics/summary?period=9999d"));
    expect((await unknown.json()).engagement.dailyActive).toHaveLength(7);
  });

  it("returns 429 when the admin-read quota is exhausted", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    expect((await GET(reqUrl("http://localhost/api/analytics/summary"))).status).toBe(429);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/analytics/summary"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/analytics/summary"))).status).toBe(403);
  });
});
