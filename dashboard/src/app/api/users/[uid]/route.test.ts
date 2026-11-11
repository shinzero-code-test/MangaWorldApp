import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PATCH } from "./route";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { wouldStrandLastSuperAdmin } from "@/lib/auth";
import { logSecurityEvent } from "@/lib/security";
import { tryClaimUsername } from "@/lib/username";
import {
  req,
  uidParams,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
  mockRoleMfaRequired,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/users/[uid] (moderator GET, super-admin PATCH).
//
// Pinned contracts:
//  - the role gate (401 unauthenticated / 403 wrong role / 403 no MFA grant)
//  - the self-modify guard (an admin may not change their own role or
//    disable their own account)
//  - the last-super-admin strand guard
//  - role change → setCustomUserClaims + revokeRefreshTokens + audit
//  - U-31 audit parity: a disable toggle writes `account_disable_toggle`
//    (the same event name as users/route.ts PATCH and users/[uid]/ban)
//  - username rules (app UsernameRules mirror) + claim conflict → 409
//  - email change → emailVerified reset + token revocation + audit
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
  return { ...actual, logSecurityEvent: vi.fn().mockResolvedValue(undefined) };
});
vi.mock("@/lib/username", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/username")>();
  return {
    ...actual,
    tryClaimUsername: vi.fn().mockResolvedValue(true),
    releaseUsernameIfOwned: vi.fn().mockResolvedValue(undefined),
  };
});

function mockAuth() {
  const auth = {
    getUser: vi.fn().mockResolvedValue({
      uid: "uid-1",
      email: "user@example.com",
      displayName: "User",
      emailVerified: true,
      disabled: false,
      phoneNumber: "+10000000000",
      customClaims: { role: "viewer" },
      providerData: [{ providerId: "password" }],
      metadata: { lastSignInTime: "2026-01-01T00:00:00Z", creationTime: "2025-01-01T00:00:00Z" },
    }),
    updateUser: vi.fn().mockResolvedValue(undefined),
    setCustomUserClaims: vi.fn().mockResolvedValue(undefined),
    revokeRefreshTokens: vi.fn().mockResolvedValue(undefined),
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  fake.put("publicProfiles", "uid-1", {
    username: "oldname",
    bio: "hello",
    isPublic: true,
  });
  fake.setCount("users/uid-1/favorites", 3);
  fake.setCount("users/uid-1/readingHistory", 7);
  fake.setCount("users/uid-1/readerAnnotations", 1);
  fake.setCount("users/uid-1/devices", 2);
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
  vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(false);
  vi.mocked(tryClaimUsername).mockResolvedValue(true);
});

describe("GET /api/users/[uid]", () => {
  it("returns the profile, stats and activity for a moderator", async () => {
    mockAuth();
    fake.put("users/uid-1/readingHistory", "h1", { chapterUrl: "/c/1" });
    fake.put("users/uid-1/lists", "l1", { title: "Reading" });

    const res = await GET(req({}), uidParams("uid-1"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.uid).toBe("uid-1");
    expect(body.email).toBe("user@example.com");
    expect(body.role).toBe("viewer");
    expect(body.username).toBe("oldname");
    expect(body.bio).toBe("hello");
    expect(body.favoriteCount).toBe(3);
    expect(body.historyCount).toBe(7);
    expect(body.annotationCount).toBe(1);
    expect(body.deviceCount).toBe(2);
    expect(body.recentHistory).toHaveLength(1);
    expect(body.recentHistory[0].id).toBe("h1");
    expect(body.lists).toHaveLength(1);
    expect(body.lists[0].id).toBe("l1");
  });

  it("rejects an invalid uid with 400", async () => {
    mockAuth();
    expect((await GET(req({}), uidParams(""))).status).toBe(400);
    expect((await GET(req({}), uidParams("a/b"))).status).toBe(400);
    expect((await GET(req({}), uidParams("x".repeat(513)))).status).toBe(400);
  });

  it("returns 401 when unauthenticated", async () => {
    mockRoleUnauthorized();
    expect((await GET(req({}), uidParams("uid-1"))).status).toBe(401);
  });

  it("returns 403 for a viewer and for a missing MFA grant", async () => {
    mockRoleForbidden();
    expect((await GET(req({}), uidParams("uid-1"))).status).toBe(403);
    mockRoleMfaRequired();
    expect((await GET(req({}), uidParams("uid-1"))).status).toBe(403);
  });
});

describe("PATCH /api/users/[uid]", () => {
  it("changes a role, revokes sessions and audits", async () => {
    const auth = mockAuth();

    const res = await PATCH(req({ role: "moderator" }), uidParams("uid-1"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });
    expect(auth.setCustomUserClaims).toHaveBeenCalledWith("uid-1", { role: "moderator" });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("role_change", {
      by: "admin-1",
      target: "uid-1",
      role: "moderator",
    });
  });

  it("refuses to change the caller's own role or disable their own account", async () => {
    const auth = mockAuth();
    // requireRole resolves as admin-1 → targeting admin-1 is self-modification.
    expect((await PATCH(req({ role: "viewer" }), uidParams("admin-1"))).status).toBe(400);
    expect((await PATCH(req({ disabled: true }), uidParams("admin-1"))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("refuses to strand the last super-admin", async () => {
    const auth = mockAuth();
    vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(true);

    expect((await PATCH(req({ role: "viewer" }), uidParams("uid-1"))).status).toBe(400);
    expect((await PATCH(req({ disabled: true }), uidParams("uid-1"))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("rejects an invalid role with 400", async () => {
    const auth = mockAuth();
    expect((await PATCH(req({ role: "root" }), uidParams("uid-1"))).status).toBe(400);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
  });

  it("disables an account, revokes its tokens and writes the disable audit event", async () => {
    const auth = mockAuth();

    const res = await PATCH(req({ disabled: true }), uidParams("uid-1"));
    expect(res.status).toBe(200);
    expect(auth.updateUser).toHaveBeenCalledWith("uid-1", { disabled: true });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    // U-31 audit parity with users/route.ts PATCH and users/[uid]/ban.
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("account_disable_toggle", {
      by: "admin-1",
      target: "uid-1",
      disabled: true,
    });
  });

  it("rejects a non-boolean disabled flag", async () => {
    const auth = mockAuth();
    expect((await PATCH(req({ disabled: "yes" }), uidParams("uid-1"))).status).toBe(400);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("claims a new username, releases the old one and audits", async () => {
    mockAuth();

    const res = await PATCH(req({ username: "new_name" }), uidParams("uid-1"));
    expect(res.status).toBe(200);
    expect(vi.mocked(tryClaimUsername)).toHaveBeenCalledWith("uid-1", "new_name");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("admin_username_change", {
      by: "admin-1",
      target: "uid-1",
      from: "oldname",
      to: "new_name",
    });
  });

  it("rejects a username the app would reject and a taken username with 409", async () => {
    mockAuth();
    // "ab" is shorter than the app's minimum, "bad-name" uses a forbidden char.
    expect((await PATCH(req({ username: "ab" }), uidParams("uid-1"))).status).toBe(400);
    expect((await PATCH(req({ username: "bad-name" }), uidParams("uid-1"))).status).toBe(400);
    vi.mocked(tryClaimUsername).mockResolvedValue(false);
    expect((await PATCH(req({ username: "new_name" }), uidParams("uid-1"))).status).toBe(409);
  });

  it("validates the bio length and the isPublic flag", async () => {
    mockAuth();
    expect((await PATCH(req({ bio: "x".repeat(1001) }), uidParams("uid-1"))).status).toBe(400);
    expect((await PATCH(req({ isPublic: "yes" }), uidParams("uid-1"))).status).toBe(400);
    expect((await PATCH(req({ bio: "ok", isPublic: false }), uidParams("uid-1"))).status).toBe(200);
  });

  it("changes an email, resets its verification, revokes sessions and audits", async () => {
    const auth = mockAuth();

    const res = await PATCH(req({ email: "new@example.com" }), uidParams("uid-1"));
    expect(res.status).toBe(200);
    expect(auth.updateUser).toHaveBeenCalledWith("uid-1", {
      email: "new@example.com",
      emailVerified: false,
    });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("admin_email_change", {
      by: "admin-1",
      target: "uid-1",
    });
  });

  it("rejects a malformed email with 400 before touching Auth", async () => {
    const auth = mockAuth();
    expect((await PATCH(req({ email: "not-an-email" }), uidParams("uid-1"))).status).toBe(400);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await PATCH(req({ role: "moderator" }), uidParams("uid-1"))).status).toBe(401);
    mockRoleForbidden();
    expect((await PATCH(req({ role: "moderator" }), uidParams("uid-1"))).status).toBe(403);
  });
});
