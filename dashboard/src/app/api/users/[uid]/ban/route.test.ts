import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminAuth } from "@/lib/firebase-admin";
import { wouldStrandLastSuperAdmin } from "@/lib/auth";
import { logSecurityEvent } from "@/lib/security";
import { req, uidParams, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden, mockRoleMfaRequired } from "@/app/api/__tests__/route-harness";

// (3) users/[uid]/ban (U-29 landed guards — the working tree carries the
// self-ban guard, last-super-admin guard, token revocation, and audit
// logging; these tests pin all four so a refactor cannot silently drop
// one): super-admin-only, strict boolean, self-ban 400, last-admin 409,
// updateUser + revokeRefreshTokens, account_disable_toggle audit event.
// genericErrorResponse stays REAL so 401/403 mapping is under test.
vi.mock("@/lib/auth", () => ({
  requireRole: vi.fn(),
  wouldStrandLastSuperAdmin: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    logSecurityEvent: vi.fn().mockResolvedValue(undefined),
  };
});

function mockAuth() {
  const auth = {
    updateUser: vi.fn().mockResolvedValue(undefined),
    revokeRefreshTokens: vi.fn().mockResolvedValue(undefined),
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
  vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(false);
});

describe("POST /api/users/[uid]/ban", () => {
  it("bans a user, revokes refresh tokens, and audit-logs", async () => {
    const auth = mockAuth();

    const res = await POST(req({ banned: true }), uidParams("uid-target"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, banned: true });
    expect(auth.updateUser).toHaveBeenCalledWith("uid-target", { disabled: true });
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-target");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("account_disable_toggle", {
      by: "admin-1",
      target: "uid-target",
      disabled: true,
    });
  });

  it("unbans without revoking and still audits", async () => {
    const auth = mockAuth();

    const res = await POST(req({ banned: false }), uidParams("uid-target"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, banned: false });
    expect(auth.updateUser).toHaveBeenCalledWith("uid-target", { disabled: false });
    expect(auth.revokeRefreshTokens).not.toHaveBeenCalled();
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("account_disable_toggle", {
      by: "admin-1",
      target: "uid-target",
      disabled: false,
    });
  });

  it("refuses self-ban with 400", async () => {
    const auth = mockAuth();
    // requireRole resolves admin-1; banning admin-1 is a self-ban.
    const res = await POST(req({ banned: true }), uidParams("admin-1"));
    expect(res.status).toBe(400);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("refuses to strand the last super-admin with 409", async () => {
    const auth = mockAuth();
    vi.mocked(wouldStrandLastSuperAdmin).mockResolvedValue(true);

    const res = await POST(req({ banned: true }), uidParams("uid-target"));
    expect(res.status).toBe(409);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("still allows unban when the target is the last super-admin", async () => {
    const auth = mockAuth();
    // Unban path never consults wouldStrandLastSuperAdmin (not a demotion).
    const res = await POST(req({ banned: false }), uidParams("uid-target"));
    expect(res.status).toBe(200);
    expect(vi.mocked(wouldStrandLastSuperAdmin)).not.toHaveBeenCalled();
    expect(auth.updateUser).toHaveBeenCalledWith("uid-target", { disabled: false });
  });

  it("rejects non-boolean banned payloads with 400", async () => {
    const auth = mockAuth();
    expect((await POST(req({ banned: "true" }), uidParams("uid-target"))).status).toBe(400);
    expect((await POST(req({}), uidParams("uid-target"))).status).toBe(400);
    expect((await POST(req({ banned: 1 }), uidParams("uid-target"))).status).toBe(400);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("rejects invalid uids with 400", async () => {
    const auth = mockAuth();
    expect((await POST(req({ banned: true }), uidParams(""))).status).toBe(400);
    expect((await POST(req({ banned: true }), uidParams("x".repeat(129)))).status).toBe(400);
    expect(auth.updateUser).not.toHaveBeenCalled();
  });

  it("returns 401 when unauthenticated", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ banned: true }), uidParams("uid-target"))).status).toBe(401);
  });

  it("returns 403 for non-super-admin roles", async () => {
    mockRoleForbidden();
    expect((await POST(req({ banned: true }), uidParams("uid-target"))).status).toBe(403);
  });

  it("returns 403 when the MFA grant is missing", async () => {
    mockRoleMfaRequired();
    expect((await POST(req({ banned: true }), uidParams("uid-target"))).status).toBe(403);
  });

  it("surfaces updateUser failures as 500", async () => {
    mockAuth();
    vi.mocked(getAdminAuth).mockReturnValue({
      updateUser: vi.fn().mockRejectedValue(new Error("auth backend down")),
    } as never);
    const res = await POST(req({ banned: true }), uidParams("uid-target"));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("auth backend");
  });
});
