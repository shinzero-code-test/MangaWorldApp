import "../../__tests__/env-google-client-id";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminAuth } from "@/lib/firebase-admin";
import { consumeRateLimit, logSecurityEvent } from "@/lib/security";
import { ensureDashboardProfile } from "@/lib/username";
import { req } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/auth/google (the Google/email session pipeline).
//
// Pinned contracts:
//  - 400 for a missing / empty / oversized ID token
//  - 429 on the IP bucket, on the strict password buckets and on the
//    email bucket (XFF rotation must not buy a fresh throttle)
//  - 403 for a VIEWER — the dashboard is not for viewers, and the signed-in
//    email is echoed so a wrong-account login is diagnosable
//  - super-admin auto-promotion ONLY for the configured address AND only
//    when email_verified is true (config drift must not elevate an
//    unverified account)
//  - 200 → a 24h session cookie + a username-rules-compliant profile
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    consumeRateLimit: vi.fn().mockResolvedValue({ allowed: true }),
    logSecurityEvent: vi.fn().mockResolvedValue(undefined),
  };
});
vi.mock("@/lib/username", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/username")>();
  return { ...actual, ensureDashboardProfile: vi.fn().mockResolvedValue("newuser") };
});

function decoded(overrides: Record<string, unknown> = {}) {
  return {
    uid: "uid-1",
    email: "mod@example.com",
    email_verified: true,
    name: "Mod",
    picture: "https://cdn.example/a.png",
    role: "moderator",
    firebase: { sign_in_provider: "google.com" },
    ...overrides,
  };
}

function mockAuth(token: Record<string, unknown> = decoded()) {
  const auth = {
    verifyIdToken: vi.fn().mockResolvedValue(token),
    createSessionCookie: vi.fn().mockResolvedValue("session-cookie"),
    getUser: vi.fn().mockResolvedValue({ uid: "uid-1", customClaims: {} }),
    setCustomUserClaims: vi.fn().mockResolvedValue(undefined),
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(ensureDashboardProfile).mockResolvedValue("newuser");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("POST /api/auth/google", () => {
  it("issues a 24h session cookie for a moderator", async () => {
    const auth = mockAuth();

    const res = await POST(req({ idToken: "google-token" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, role: "moderator" });

    const session = res.cookies.get("session");
    expect(session?.value).toBe("session-cookie");
    expect(session?.maxAge).toBe(60 * 60 * 24);
    expect(session?.httpOnly).toBe(true);
    // The stale MFA grant must not survive a fresh login.
    expect(res.cookies.get("mfa_grant")?.value).toBe("");
    expect(vi.mocked(ensureDashboardProfile)).toHaveBeenCalledWith("uid-1", "Mod", expect.anything());
    expect(auth.createSessionCookie).toHaveBeenCalledWith("google-token", { expiresIn: 60 * 60 * 24 * 1000 });
  });

  it("rejects a missing or oversized ID token with 400", async () => {
    mockAuth();
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ idToken: "" }))).status).toBe(400);
    expect((await POST(req({ idToken: "x".repeat(8193) }))).status).toBe(400);
  });

  it("returns 429 when the IP bucket is exhausted", async () => {
    mockAuth();
    vi.mocked(consumeRateLimit).mockResolvedValueOnce({ allowed: false });
    expect((await POST(req({ idToken: "t" }))).status).toBe(429);
  });

  it("applies the strict password buckets to a password-provider token", async () => {
    mockAuth(decoded({ firebase: { sign_in_provider: "password" } }));
    vi.mocked(consumeRateLimit)
      .mockResolvedValueOnce({ allowed: true }) // ip
      .mockResolvedValueOnce({ allowed: false }) // strict ip
      .mockResolvedValueOnce({ allowed: true }); // strict email

    const res = await POST(req({ idToken: "t" }));
    expect(res.status).toBe(429);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("auth_password_throttled", {
      email: "mod@example.com",
    });
  });

  it("returns 429 when the email bucket is exhausted", async () => {
    mockAuth();
    vi.mocked(consumeRateLimit)
      .mockResolvedValueOnce({ allowed: true }) // ip
      .mockResolvedValueOnce({ allowed: false }); // email

    expect((await POST(req({ idToken: "t" }))).status).toBe(429);
  });

  it("refuses a viewer with 403 and echoes the signed-in email", async () => {
    mockAuth(decoded({ role: "viewer" }));

    const res = await POST(req({ idToken: "t" }));
    expect(res.status).toBe(403);
    const body = await res.json();
    expect(body.email).toBe("mod@example.com");
    expect(res.cookies.get("session")).toBeUndefined();
  });

  it("promotes the configured super-admin only when the email is verified", async () => {
    const auth = mockAuth(
      decoded({ email: "owner@example.com", role: "viewer", email_verified: true }),
    );
    vi.stubEnv("SUPER_ADMIN_EMAIL", "owner@example.com");

    const res = await POST(req({ idToken: "t" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ refreshRequired: true });
    expect(auth.setCustomUserClaims).toHaveBeenCalledWith("uid-1", { role: "super-admin" });
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("super_admin_auto_promotion", {
      uid: "uid-1",
      email: "owner@example.com",
    });
  });

  it("never promotes an UNVERIFIED account that matches the configured address", async () => {
    const auth = mockAuth(
      decoded({ email: "owner@example.com", role: "viewer", email_verified: false }),
    );
    vi.stubEnv("SUPER_ADMIN_EMAIL", "owner@example.com");

    const res = await POST(req({ idToken: "t" }));
    // A viewer is still refused — and never elevated.
    expect(res.status).toBe(403);
    expect(auth.setCustomUserClaims).not.toHaveBeenCalled();
  });

  it("returns 401 when the ID token cannot be verified", async () => {
    mockAuth();
    vi.mocked(getAdminAuth).mockReturnValue({
      verifyIdToken: vi.fn().mockRejectedValue(new Error("bad token")),
    } as never);

    const res = await POST(req({ idToken: "t" }));
    expect(res.status).toBe(401);
    expect(JSON.stringify(await res.json())).not.toContain("bad token");
  });
});
