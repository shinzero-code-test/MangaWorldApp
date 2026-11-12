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
//  - the reading-hour distribution is derived from the SERVER-verified reading
//    evidence (the cloud reading positions) and always sums to 100%.
//    It used to come from the client-written `user_achievements.lastUpdated`,
//    which the owner can forge (p3-next 2.5 / U-31).
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

const DAY_MS = 86_400_000;

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
    // The server-verified reading evidence (one reading position).
    fake.putGroupAt(
      "preferences",
      "reading_positions_0",
      {
        "m1_1": { mangaId: "m1", chapterNumber: 1, currentPage: 4, totalPages: 20, updatedAt: Date.now() },
      },
      "users/uid-1/preferences/reading_positions_0",
    );
    // A forged client document must NOT move the distribution.
    fake.put("user_achievements", "uid-2", { lastUpdated: Date.now() - 5 * DAY_MS });

    const res = await GET(reqUrl("http://localhost/api/analytics/engagement?days=3"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.dailyActive).toHaveLength(3);
    expect(body.topManga[0]).toEqual({ name: "Popular", reads: 5 });
    const pctSum = body.readingDistribution.reduce((s: number, d: { pct: number }) => s + d.pct, 0);
    expect(pctSum).toBe(100);
    // Only the verified reader is counted — the forged document is ignored.
    expect(body.readingDistribution.filter((d: { pct: number }) => d.pct > 0)).toHaveLength(1);
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
