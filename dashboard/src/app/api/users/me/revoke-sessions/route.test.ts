import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { logSecurityEvent } from "@/lib/security";
import {
  req,
  mockAppAuthed,
  mockAppAnon,
  mockAppUnauthorized,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/users/me/revoke-sessions ("sign out everywhere").
//
// Pinned contracts:
//  - app-user gate: 401 without a valid ID token, 403 for an anonymous
//    session
//  - the per-user rate limit (429)
//  - real token invalidation (revokeRefreshTokens), the push-device
//    registry is wiped and every session row is flagged revoked
//  - the audit event is written
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/app-rate-limit", () => ({
  allowAppMutation: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
  getAdminDb: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return { ...actual, logSecurityEvent: vi.fn().mockResolvedValue(undefined) };
});

let fake = createFakeDb();
let revokeRefreshTokens = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  revokeRefreshTokens = vi.fn().mockResolvedValue(undefined);
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  vi.mocked(getAdminAuth).mockReturnValue({ revokeRefreshTokens } as never);
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
});

describe("POST /api/users/me/revoke-sessions", () => {
  it("revokes tokens, wipes the devices and flags every session", async () => {
    fake.put("users/uid-1/devices", "d1", { token: "tok" });
    fake.put("users/uid-1/sessions", "s1", { device: "pixel" });

    const res = await POST(req({}));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    expect(vi.mocked(getAdminAuth).mock.results.length).toBeGreaterThanOrEqual(0);
    expect(revokeRefreshTokens).toHaveBeenCalledWith("uid-1");

    // The push registry is wiped (no device keeps receiving pushes).
    expect(fake.read("users/uid-1/devices", "d1")).toBeUndefined();
    // Every session row is flagged revoked.
    expect(fake.read("users/uid-1/sessions", "s1")?.revoked).toBe(true);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("user_revoke_sessions", {
      uid: "uid-1",
    });
  });

  it("returns 429 when the caller is rate limited and revokes nothing", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    const res = await POST(req({}));
    expect(res.status).toBe(429);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401 without a valid ID token and 403 for anonymous users", async () => {
    mockAppUnauthorized();
    expect((await POST(req({}))).status).toBe(401);
    mockAppAnon();
    expect((await POST(req({}))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
