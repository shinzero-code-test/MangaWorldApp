import "../../__tests__/env-google-client-id";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminAuth } from "@/lib/firebase-admin";
import { consumeRateLimit } from "@/lib/security";
import { req } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/auth/google-credential (the GIS escape hatch that
// exchanges a Google ID token for a Firebase custom token).
//
// Pinned contracts:
//  - 500 when the Google client id is not configured
//  - 400 for a missing / empty / oversized credential
//  - 429 on the per-IP and per-email buckets
//  - 401 when Google's tokeninfo rejects the token
//  - U-29: 401 when `exp` is missing, expired, or implausibly far in the
//    future (tokeninfo returning 200 is NOT proof of freshness)
//  - 401 when the email is unverified, the issuer is wrong or the audience
//    is not our client id
//  - no open enrolment: an unknown Google address may only be bootstrapped
//    when it is the configured super-admin
//  - an account that was NOT linked to google.com is refused (it may have
//    been registered with email/password for the same address)
//  - 200 → a Firebase custom token
vi.mock("@/lib/firebase-admin", () => ({
  getAdminAuth: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return { ...actual, consumeRateLimit: vi.fn() };
});

const CLIENT_ID = "test-google-client-id.apps.googleusercontent.com";
const NOW_SECONDS = () => Math.floor(Date.now() / 1000);

function tokenInfo(overrides: Record<string, unknown> = {}) {
  return {
    aud: CLIENT_ID,
    iss: "https://accounts.google.com",
    email: "admin@example.com",
    email_verified: "true",
    name: "Admin",
    picture: "https://cdn.example/a.png",
    exp: String(NOW_SECONDS() + 3600),
    ...overrides,
  };
}

function mockFetchOk(info: Record<string, unknown>) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => info,
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function mockAuth(overrides: Record<string, unknown> = {}) {
  const auth = {
    getUserByEmail: vi.fn().mockResolvedValue({ uid: "admin-1" }),
    getUser: vi.fn().mockResolvedValue({
      uid: "admin-1",
      emailVerified: true,
      providerData: [{ providerId: "google.com" }],
    }),
    createUser: vi.fn(),
    updateUser: vi.fn().mockResolvedValue(undefined),
    createCustomToken: vi.fn().mockResolvedValue("custom-token-123"),
    ...overrides,
  };
  vi.mocked(getAdminAuth).mockReturnValue(auth as never);
  return auth;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
  mockFetchOk(tokenInfo());
  mockAuth();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("POST /api/auth/google-credential", () => {
  it("exchanges a verified Google token for a custom token", async () => {
    const auth = mockAuth();

    const res = await POST(req({ credential: "google-id-token" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ customToken: "custom-token-123" });
    expect(auth.createCustomToken).toHaveBeenCalledWith("admin-1");
  });

  it("rejects a missing, empty or oversized credential with 400", async () => {
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ credential: "" }))).status).toBe(400);
    expect((await POST(req({ credential: "x".repeat(8193) }))).status).toBe(400);
  });

  it("returns 429 when the IP bucket is exhausted", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValueOnce({ allowed: false });
    const res = await POST(req({ credential: "google-id-token" }));
    expect(res.status).toBe(429);
  });

  it("returns 429 when the per-email bucket is exhausted", async () => {
    vi.mocked(consumeRateLimit)
      .mockResolvedValueOnce({ allowed: true })
      .mockResolvedValueOnce({ allowed: false });
    const res = await POST(req({ credential: "google-id-token" }));
    expect(res.status).toBe(429);
  });

  it("returns 401 when Google's tokeninfo rejects the token", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }));

    const res = await POST(req({ credential: "google-id-token" }));
    expect(res.status).toBe(401);
  });

  it("returns 401 when exp is missing, expired or implausibly far in the future (U-29)", async () => {
    mockFetchOk(tokenInfo({ exp: undefined }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ exp: String(NOW_SECONDS() - 60) }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ exp: String(NOW_SECONDS() + 3 * 3600) }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ exp: "not-a-number" }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);
  });

  it("returns 401 for an unverified email, a wrong issuer or a wrong audience", async () => {
    mockFetchOk(tokenInfo({ email_verified: "false" }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ iss: "https://evil.example" }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ aud: "someone-else.apps.googleusercontent.com" }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);

    mockFetchOk(tokenInfo({ email: "" }));
    expect((await POST(req({ credential: "t" }))).status).toBe(401);
  });

  it("refuses an unknown Google address (no open enrolment)", async () => {
    const auth = mockAuth({
      getUserByEmail: vi.fn().mockRejectedValue(Object.assign(new Error("nope"), { code: "auth/user-not-found" })),
    });
    vi.stubEnv("SUPER_ADMIN_EMAIL", "owner@example.com");
    mockFetchOk(tokenInfo({ email: "stranger@example.com" }));

    const res = await POST(req({ credential: "t" }));
    expect(res.status).toBe(401);
    expect(auth.createUser).not.toHaveBeenCalled();
  });

  it("bootstraps the configured super-admin and creates the Firebase account", async () => {
    const auth = mockAuth({
      getUserByEmail: vi.fn().mockRejectedValue(Object.assign(new Error("nope"), { code: "auth/user-not-found" })),
      createUser: vi.fn().mockResolvedValue({ uid: "new-admin" }),
    });
    vi.stubEnv("SUPER_ADMIN_EMAIL", "owner@example.com");
    mockFetchOk(tokenInfo({ email: "owner@example.com" }));

    const res = await POST(req({ credential: "t" }));
    expect(res.status).toBe(200);
    expect(auth.createUser).toHaveBeenCalled();
    expect(auth.createCustomToken).toHaveBeenCalledWith("new-admin");
  });

  it("refuses an account that is not linked to google.com", async () => {
    mockAuth({
      getUser: vi.fn().mockResolvedValue({
        uid: "admin-1",
        emailVerified: true,
        providerData: [{ providerId: "password" }],
      }),
    });

    const res = await POST(req({ credential: "t" }));
    expect(res.status).toBe(401);
  });

  it("returns 500 when the Google client id is not configured", async () => {
    vi.stubEnv("NEXT_PUBLIC_GOOGLE_CLIENT_ID", "");
    vi.resetModules();
    const fresh = await import("./route");
    const res = await fresh.POST(req({ credential: "t" }));
    expect(res.status).toBe(500);
  });
});
