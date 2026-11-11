import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/notifications/tokens (the FCM token inventory).
//
// Pinned contracts:
//  - super-admin gate (401/403)
//  - FCM TOKENS ARE CREDENTIALS: the response only carries a masked token
//    and its last 8 characters — a usable token must never leave the server
//  - rows without a usable token suffix are dropped
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
});

describe("GET /api/notifications/tokens", () => {
  it("masks the tokens and exposes only the suffix", async () => {
    fake.putGroup("devices", "d1", {
      token: "0123456789abcdefGHIJ",
      platform: "android",
      updatedAt: 42,
    });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.tokens).toHaveLength(1);
    // Only the last 8 characters survive, behind a mask.
    expect(body.tokens[0].tokenMasked).toBe("••••••••cdefGHIJ");
    expect(body.tokens[0].tokenSuffix).toBe("cdefGHIJ");
    expect(body.tokens[0].platform).toBe("android");
    // The raw credential must not appear anywhere in the response.
    expect(JSON.stringify(body)).not.toContain("0123456789");
  });

  it("drops rows without a token", async () => {
    fake.putGroup("devices", "d1", { platform: "android" });

    const res = await GET();
    expect(res.status).toBe(200);
    expect((await res.json()).tokens).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
