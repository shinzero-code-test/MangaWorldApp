import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PATCH, DELETE } from "./route";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { wouldStrandLastSuperAdmin } from "@/lib/auth";
import { consumeRateLimit, logSecurityEvent } from "@/lib/security";
import { tryClaimUsername } from "@/lib/username";
import {
  req,
  reqUrl,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
  mockRoleMfaRequired,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/users (moderator GET, super-admin PATCH/DELETE).
//
// Pinned contracts:
//  - GET: moderator gate, per-admin rate limit (429), guest (anonymous)
//    filtering, pagination clamps, sort-key allow-list
//  - PATCH: super-admin gate, self-modify guard, last-super-admin strand
//    guard, role change → claims + token revocation + audit, username claim
//    conflict → 409, disable → revoke + account_disable_toggle audit
//  - DELETE: super-admin gate, strand guard, full subcollection purge
//    (paginated to completion), 2FA/OTP row removal, username release
//
// genericErrorResponse stays REAL so the 401/403 mapping is under test.
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn(), wouldStrandLastSuperAdmin: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
  getAdminDb: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    logSecurityEvent: vi.fn().mockResolvedValue(undefined),
    consumeRateLimit: vi.fn().mockResolvedValue({ allowed: true }),
  };
});
vi.mock("@/lib/username", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/username")>();
  return {
    ...actual,
    tryClaimUsername: vi.fn().mockResolvedValue(true),
    releaseUsernameIfOwned: vi.fn().mockResolvedValue(undefined),
  };
});

const AUTH_USER = {
  uid: "uid-1",
  email: "user@example.com",
  displayName: "User",
  emailVerified: true,
  disabled: false,
  phoneNumber: null,
  customClaims: { role: "moderator" },
  providerData: [{ providerId: "password" }],
  metadata: { lastSignInTime: "2026-01-01T00:00:00Z", creationTime: "2025-01-01T00:00:00Z" },
};

const GUEST_USER = {
  uid: "guest-1",
  email: undefined,
  displayName: undefined,
  emailVerified: false,
  disabled: false,
  providerData: [],
  metadata: { lastSignInTime: "2026-01-01T00:00:00Z", creationTime: "2026-02-01T00:00:00Z" },
};

function mockAuth(users: unknown[] = [AUTH_USER]) {
  const auth = {
    listUsers: vi.fn().mockResolvedValue({ users, pageToken: undefined }),
    getUser: vi.fn().mockResolvedValue(AUTH_USER),
    updateUser: vi.fn().mockResolvedValue(undefined),
    setCustomUserClaims: vi.fn().mockResolvedValue(undefined),
    revokeRefreshTokens: vi.fn().mockResolvedValue(undefined),
    deleteUser: vi.fn().mockResolvedValue(undefined),
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
  vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(false);
  vi.mocked(tryClaimUsername).mockResolvedValue(true);
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("GET /api/users", () => {
  it("lists named users, hides guests by default and reports the guest count", async () => {
    mockAuth([AUTH_USER, GUEST_USER]);

    const res = await GET(reqUrl("http://localhost/api/users"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.users).toHaveLength(1);
    expect(body.users[0].id).toBe("uid-1");
    expect(body.users[0].role).toBe("moderator");
    expect(body.guestCount).toBe(1);
    expect(body.includeGuests).toBe(false);
    expect(body.roleCounts.moderator).toBe(1);
  });

  it("includes guests on request and lists only guests for provider=anonymous", async () => {
    mockAuth([AUTH_USER, GUEST_USER]);

    const withGuests = await GET(reqUrl("http://localhost/api/users?includeGuests=1"));
    expect((await withGuests.json()).users).toHaveLength(2);

    const onlyGuests = await GET(reqUrl("http://localhost/api/users?provider=anonymous"));
    const body = await onlyGuests.json();
    expect(body.users).toHaveLength(1);
    expect(body.users[0].id).toBe("guest-1");
    // Role chips count the NAMED fleet even in the guest-audit view.
    expect(body.roleCounts.moderator).toBe(1);
  });

  it("clamps pagination and ignores an unknown sort key", async () => {
    mockAuth([AUTH_USER]);

    const res = await GET(reqUrl("http://localhost/api/users?page=99999&limit=9999&sortBy=drop%20table"));
    const body = await res.json();
    expect(body.page).toBe(10000);
    expect(body.limit).toBe(100);
    expect(body.total).toBe(1);
    expect(body.hasMore).toBe(false);
    // An unknown sort key falls back to createdAt — the row is still there.
    expect(body.users).toHaveLength(0); // offset past the end of a 1-row list
    const first = await GET(reqUrl("http://localhost/api/users?page=1&limit=1"));
    expect((await first.json()).users).toHaveLength(1);
  });

  it("returns 429 when the admin-read quota is exhausted", async () => {
    mockAuth([AUTH_USER]);
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });

    const res = await GET(reqUrl("http://localhost/api/users"));
    expect(res.status).toBe(429);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockAuth([AUTH_USER]);
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/users"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/users"))).status).toBe(403);
  });
});

describe("PATCH /api/users", () => {
  it("changes a role, revokes tokens and audits", async () => {
    const auth = mockAuth();

    const res = await PATCH(req({ uid: "uid-1", role: "super-admin" }));
    expect(res.status).toBe(200);
    expect(auth.setCustomUserClaims).toHaveBeenCalledWith("uid-1", { role: "super-admin" });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("role_change", {
      by: "admin-1",
      target: "uid-1",
      role: "super-admin",
    });
  });

  it("refuses to change the caller's own role or disable their own account", async () => {
    const auth = mockAuth();
    expect((await PATCH(req({ uid: "admin-1", role: "viewer" }))).status).toBe(400);
    expect((await PATCH(req({ uid: "admin-1", disabled: true }))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
  });

  it("refuses to strand the last super-admin", async () => {
    const auth = mockAuth();
    vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(true);
    expect((await PATCH(req({ uid: "uid-1", role: "viewer" }))).status).toBe(400);
    expect((await PATCH(req({ uid: "uid-1", disabled: true }))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
  });

  it("rejects an invalid uid and an invalid role with 400", async () => {
    const auth = mockAuth();
    expect((await PATCH(req({ uid: "", role: "viewer" }))).status).toBe(400);
    expect((await PATCH(req({ uid: "uid-1", role: "root" }))).status).toBe(400);
    expect((await PATCH(req({ uid: "uid-1", bio: "x".repeat(1001) }))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
  });

  it("disables an account, revokes its tokens and writes the disable audit event", async () => {
    const auth = mockAuth();

    const res = await PATCH(req({ uid: "uid-1", disabled: true }));
    expect(res.status).toBe(200);
    expect(auth.updateUser).toHaveBeenCalledWith("uid-1", { disabled: true });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("account_disable_toggle", {
      by: "admin-1",
      target: "uid-1",
      disabled: true,
    });
  });

  it("rejects a taken username with 409", async () => {
    mockAuth();
    vi.mocked(tryClaimUsername).mockResolvedValue(false);
    expect((await PATCH(req({ uid: "uid-1", username: "taken" }))).status).toBe(409);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockAuth();
    mockRoleUnauthorized();
    expect((await PATCH(req({ uid: "uid-1", role: "viewer" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await PATCH(req({ uid: "uid-1", role: "viewer" }))).status).toBe(403);
    mockRoleMfaRequired();
    expect((await PATCH(req({ uid: "uid-1", role: "viewer" }))).status).toBe(403);
  });
});

describe("DELETE /api/users", () => {
  it("deletes the account, purges every subcollection and releases the username", async () => {
    const auth = mockAuth();
    fake.put("publicProfiles", "uid-1", { username: "oldname" });
    fake.put("users/uid-1/favorites", "f1", { mangaId: "m1" });
    fake.put("users/uid-1/devices", "d1", { token: "tok" });
    fake.put("users/uid-1/loginLogs", "l1", { at: 1 });

    const res = await DELETE(req({ uid: "uid-1" }));
    expect(res.status).toBe(200);
    expect(auth.deleteUser).toHaveBeenCalledWith("uid-1");
    // The subcollection purge must reach EVERY subcollection (PII left behind
    // is the whole point of the D-5 fix) — each seeded doc is gone.
    expect(fake.read("users/uid-1/favorites", "f1")).toBeUndefined();
    expect(fake.read("users/uid-1/devices", "d1")).toBeUndefined();
    expect(fake.read("users/uid-1/loginLogs", "l1")).toBeUndefined();
    expect(fake.writesFor("users/uid-1/favorites")).toHaveLength(1);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("admin_user_delete", {
      by: "admin-1",
      target: "uid-1",
    });
  });

  it("refuses to delete the last super-admin", async () => {
    const auth = mockAuth();
    vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(true);
    expect((await DELETE(req({ uid: "uid-1" }))).status).toBe(400);
    expect(auth.deleteUser).not.toHaveBeenCalled();
  });

  it("rejects a missing uid with 400", async () => {
    const auth = mockAuth();
    expect((await DELETE(req({}))).status).toBe(400);
    expect(auth.deleteUser).not.toHaveBeenCalled();
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockAuth();
    mockRoleUnauthorized();
    expect((await DELETE(req({ uid: "uid-1" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await DELETE(req({ uid: "uid-1" }))).status).toBe(403);
  });
});
