import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { getDashboardRoleCounts } from "@/lib/auth";
import { consumeRateLimit } from "@/lib/security";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/dashboard (the operator's landing read-out).
//
// Pinned contracts:
//  - moderator gate (401/403) — a viewer must never see fleet stats
//  - the per-admin quota-burn guard (429)
//  - the aggregates are counted from Firestore (users, open reports,
//    recent sign-ups) and the role chips come from the Auth fleet
//  - the service-health block reports Firestore and Crashlytics separately
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
  vi.mocked(getDashboardRoleCounts).mockResolvedValue({
    viewer: 1,
    moderator: 2,
    "super-admin": 3,
  });
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("GET /api/dashboard", () => {
  it("returns the fleet aggregates and the service health", async () => {
    fake.setCount("publicProfiles", 12);
    fake.setCount("moderationReports", 4);
    fake.put("community_manga", "m1", { title: "One" });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.totalUsers).toBe(12);
    expect(body.openReports).toBe(4);
    expect(body.roleCounts).toEqual({ viewer: 1, moderator: 2, "super-admin": 3 });
    expect(body.services.db).toBe(true);
    expect(body.services.auth).toBe(true);
    expect(typeof body.services.crash).toBe("boolean");
  });

  it("returns 429 when the admin-read quota is exhausted", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    const res = await GET();
    expect(res.status).toBe(429);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
