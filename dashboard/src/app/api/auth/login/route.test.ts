import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST, DELETE } from "./route";
import { getAdminAuth } from "@/lib/firebase-admin";
import { consumeRateLimit, isSameOriginRequest } from "@/lib/security";
import { ensureDashboardProfile } from "@/lib/username";
import { req } from "@/app/api/__tests__/route-harness";

// (1) auth/login — the credential entry point: throttle (429) → Identity
// Toolkit → role gate (403 for viewers / no role) → 24h session cookie.
// DELETE revokes refresh tokens so a copied cookie dies with the logout.
vi.mock("@/lib/auth", () => ({
  clearMfaGrantCookie: vi.fn(),
  deleteCurrentMfaGrant: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
}));
vi.mock("@/lib/security", () => ({
  consumeRateLimit: vi.fn(),
  isSameOriginRequest: vi.fn().mockReturnValue(true),
  logSecurityEvent: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("@/lib/username", () => ({
  ensureDashboardProfile: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("next/headers", () => ({
  cookies: vi.fn(),
}));

function mockAuth(overrides: Record<string, unknown> = {}) {
  const auth = {
    verifyIdToken: vi.fn().mockResolvedValue({ uid: "uid-1", role: "super-admin" }),
    createSessionCookie: vi.fn().mockResolvedValue("session-cookie"),
    verifySessionCookie: vi.fn().mockResolvedValue({ uid: "uid-1" }),
    revokeRefreshTokens: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

function mockFetch(ok: boolean) {
  const fetchMock = vi.fn().mockResolvedValue(
    ok
      ? ({ ok: true, json: async () => ({ idToken: "id-token" }) } as never)
      : ({ ok: false } as never)
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("POST /api/auth/login", () => {
  it("rejects cross-origin posts with 403 before any throttle or network call", async () => {
    vi.mocked(isSameOriginRequest).mockReturnValue(false);
    const fetchSpy = mockFetch(true);
    const res = await POST(req({ email: "a@b.com", password: "secret" }));
    expect(res.status).toBe(403);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(vi.mocked(consumeRateLimit)).not.toHaveBeenCalled();
  });

  it("allows non-browser clients (no Origin/Referer) through the origin gate", async () => {
    // Real isSameOriginRequest: missing Origin+Referer → true (no ambient
    // browser cookie exists to ride, so there is no CSRF to defuse).
    const { isSameOriginRequest: realGate } = await vi.importActual<typeof import("@/lib/security")>(
      "@/lib/security"
    );
    expect(realGate(new Headers())).toBe(true);
    const sameOrigin = new Headers({ host: "admin.example.com", origin: "https://admin.example.com" });
    expect(realGate(sameOrigin)).toBe(true);
    const crossOrigin = new Headers({ host: "admin.example.com", origin: "https://evil.example.com" });
    expect(realGate(crossOrigin)).toBe(false);
  });

  it("rejects malformed credentials with 400", async () => {
    expect((await POST(req({ email: "a@b.com" }))).status).toBe(400);
    expect((await POST(req({ email: "", password: "x" }))).status).toBe(400);
    expect((await POST(req({}))).status).toBe(400);
  });

  it("rate-limits per IP with 429", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValueOnce({ allowed: false });
    const res = await POST(req({ email: "a@b.com", password: "secret" }));
    expect(res.status).toBe(429);
  });

  it("rate-limits per email with 429", async () => {
    vi.mocked(consumeRateLimit)
      .mockResolvedValueOnce({ allowed: true })
      .mockResolvedValueOnce({ allowed: false });
    const res = await POST(req({ email: "a@b.com", password: "secret" }));
    expect(res.status).toBe(429);
  });

  it("returns 401 on bad Identity Toolkit credentials", async () => {
    mockFetch(false);
    const res = await POST(req({ email: "a@b.com", password: "wrong" }));
    expect(res.status).toBe(401);
  });

  it("blocks viewers with 403", async () => {
    mockFetch(true);
    mockAuth({ verifyIdToken: vi.fn().mockResolvedValue({ uid: "uid-v", role: "viewer" }) });
    const res = await POST(req({ email: "v@b.com", password: "secret" }));
    expect(res.status).toBe(403);
  });

  it("blocks users with no dashboard role with 403", async () => {
    mockFetch(true);
    mockAuth({ verifyIdToken: vi.fn().mockResolvedValue({ uid: "uid-n" }) });
    const res = await POST(req({ email: "n@b.com", password: "secret" }));
    expect(res.status).toBe(403);
  });

  it("logs in super-admins, provisions the profile, and sets a 24h session cookie", async () => {
    mockFetch(true);
    const auth = mockAuth();
    const res = await POST(req({ email: "admin@example.com", password: "secret" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { success: boolean };
    expect(body.success).toBe(true);

    // 24h TTL (was 7d): bounds an exfiltrated cookie.
    expect(auth.createSessionCookie).toHaveBeenCalledWith("id-token", {
      expiresIn: 60 * 60 * 24 * 1000,
    });
    expect(res.headers.get("set-cookie") ?? "").toContain("session=session-cookie");
    // Dashboard profile provisioning uses the email prefix, never raw email.
    expect(vi.mocked(ensureDashboardProfile)).toHaveBeenCalledWith("uid-1", "admin");
  });

  it("logs in moderators as well", async () => {
    mockFetch(true);
    mockAuth({ verifyIdToken: vi.fn().mockResolvedValue({ uid: "uid-m", role: "moderator" }) });
    const res = await POST(req({ email: "mod@example.com", password: "secret" }));
    expect(res.status).toBe(200);
  });

  it("surfaces unexpected failures as 500 without leaking internals", async () => {
    mockFetch(true);
    mockAuth({
      verifyIdToken: vi.fn().mockRejectedValue(new Error("ILT hiccup hunter2")),
    });
    const res = await POST(req({ email: "a@b.com", password: "secret" }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("hunter2");
  });
});

describe("DELETE /api/auth/login (logout)", () => {
  it("revokes refresh tokens and clears the session cookie", async () => {
    const { cookies } = await import("next/headers");
    vi.mocked(cookies).mockResolvedValue({
      get: (name: string) => (name === "session" ? { value: "session-cookie" } : undefined),
    } as never);
    const auth = mockAuth();

    const res = await DELETE();
    expect(res.status).toBe(200);
    expect((await res.json()) as { success: boolean }).toEqual({ success: true });
    expect(auth.verifySessionCookie).toHaveBeenCalledWith("session-cookie", false);
    expect(auth.revokeRefreshTokens).toHaveBeenCalledWith("uid-1");
    expect(res.headers.get("set-cookie") ?? "").toContain("session=");
  });

  it("still logs out when the cookie is expired or forged", async () => {
    const { cookies } = await import("next/headers");
    vi.mocked(cookies).mockResolvedValue({
      get: () => ({ value: "dead-cookie" }),
    } as never);
    const auth = mockAuth({
      verifySessionCookie: vi.fn().mockRejectedValue(new Error("expired")),
    });

    const res = await DELETE();
    expect(res.status).toBe(200);
    expect(auth.revokeRefreshTokens).not.toHaveBeenCalled();
  });

  it("succeeds without a session cookie", async () => {
    const { cookies } = await import("next/headers");
    vi.mocked(cookies).mockResolvedValue({ get: () => undefined } as never);
    const auth = mockAuth();

    const res = await DELETE();
    expect(res.status).toBe(200);
    expect(auth.revokeRefreshTokens).not.toHaveBeenCalled();
  });
});
