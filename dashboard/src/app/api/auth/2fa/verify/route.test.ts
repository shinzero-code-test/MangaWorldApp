import { beforeEach, describe, expect, it, vi } from "vitest";
import { authenticator } from "otplib";
import { POST } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { createMfaGrant, setMfaGrantCookie } from "@/lib/auth";
import {
  clearOtpFailures,
  consumeUsedToken,
  isOtpLocked,
  logSecurityEvent,
  recordOtpFailure,
  upgradeLegacySecret,
} from "@/lib/security";
import { req, mockSessionUser, mockSessionStale, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/verify — enrollment completion: fresh session + live TOTP +
// single-use proof, then enabled=true and an MFA grant cookie. TOTP crypto
// is REAL (otplib-generated codes verified by the real
// verifyTotpConstantTime); only the Firestore-touching helpers are stubbed.
process.env.MFA_SESSION_SECRET = "test-secret-for-vitest-only";

vi.mock("@/lib/auth", () => ({
  getCurrentUser: vi.fn(),
  createMfaGrant: vi.fn(),
  setMfaGrantCookie: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    logSecurityEvent: vi.fn().mockResolvedValue(undefined),
    isOtpLocked: vi.fn(),
    recordOtpFailure: vi.fn(),
    clearOtpFailures: vi.fn().mockResolvedValue(undefined),
    consumeUsedToken: vi.fn(),
    upgradeLegacySecret: vi.fn().mockResolvedValue(undefined),
  };
});

const SECRET = authenticator.generateSecret(20);

function fakeDb(docData: Record<string, unknown> | undefined) {
  const updates: Array<Record<string, unknown>> = [];
  const db = {
    collection: (_name: string) => ({
      doc: (_id: string) => ({
        get: async () => ({ exists: docData !== undefined, data: () => docData }),
        update: async (data: Record<string, unknown>) => {
          updates.push(data);
        },
      }),
    }),
  };
  return { db, updates };
}

function enableMocks() {
  vi.mocked(isOtpLocked).mockResolvedValue(false);
  vi.mocked(recordOtpFailure).mockResolvedValue({ locked: false });
  vi.mocked(consumeUsedToken).mockResolvedValue(true);
  vi.mocked(createMfaGrant).mockResolvedValue("grant-1");
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSessionUser();
  enableMocks();
});

describe("POST /api/auth/2fa/verify", () => {
  it("enables 2FA on a valid token and mints the grant", async () => {
    const token = authenticator.generate(SECRET);
    const { db, updates } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ token }));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ success: true, enabled: true });
    expect(updates).toHaveLength(1);
    expect(updates[0].enabled).toBe(true);
    expect(typeof updates[0].enabledAt).toBe("number");
    expect(vi.mocked(clearOtpFailures)).toHaveBeenCalledWith("admin-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_enabled", { uid: "admin-1" });
    expect(vi.mocked(createMfaGrant)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(setMfaGrantCookie)).toHaveBeenCalledTimes(1);
  });

  it("migrates a legacy plaintext secret to encrypted-at-rest", async () => {
    const token = authenticator.generate(SECRET);
    const { db } = fakeDb({ secret: SECRET, secretEncrypted: null, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ token }));
    expect(res.status).toBe(200);
    expect(vi.mocked(upgradeLegacySecret)).toHaveBeenCalledWith("admin-1", SECRET);
  });

  it("rejects a missing or non-string token with 400", async () => {
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ token: 123456 }))).status).toBe(400);
  });

  it("returns 429 while OTP-locked, before any doc read", async () => {
    vi.mocked(isOtpLocked).mockResolvedValue(true);
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(429);
  });

  it("returns 403 on a stale session", async () => {
    mockSessionStale();
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(403);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_enable_stale_session", {
      uid: "admin-1",
    });
  });

  it("returns 400 when enrollment was never started", async () => {
    vi.mocked(getAdminDb).mockReturnValue(fakeDb(undefined).db as never);
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(400);
  });

  it("returns 400 when the stored secret cannot be resolved", async () => {
    const { db } = fakeDb({ enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(400);
  });

  it("records the failure on an invalid token with 400", async () => {
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ token: "000000" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(recordOtpFailure)).toHaveBeenCalledWith("admin-1");
    expect(vi.mocked(createMfaGrant)).not.toHaveBeenCalled();
  });

  it("refuses a replayed token with 400", async () => {
    const token = authenticator.generate(SECRET);
    vi.mocked(consumeUsedToken).mockResolvedValue(false);
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ token }));
    expect(res.status).toBe(400);
    expect(vi.mocked(createMfaGrant)).not.toHaveBeenCalled();
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(401);
  });
});
