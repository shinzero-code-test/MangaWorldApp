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
  tryConsumeBackupCode,
} from "@/lib/security";
import { req, mockSessionUser, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/validate — login-time second factor. TOTP is REAL crypto
// (otplib code vs real verifyTotpConstantTime); backup-code matching is
// REAL hashing too (matchBackupCodeHash kept from the actual module).
// tryConsumeBackupCode (atomic single-use) is stubbed: its transaction is
// proven by the Firestore-rules/emulator path, not by a unit fake.
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
    tryConsumeBackupCode: vi.fn(),
  };
});

const SECRET = authenticator.generateSecret(20);

function fakeDb(docData: Record<string, unknown> | undefined) {
  return {
    collection: (_name: string) => ({
      doc: (_id: string) => ({
        get: async () => ({ exists: docData !== undefined, data: () => docData }),
      }),
    }),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSessionUser();
  vi.mocked(isOtpLocked).mockResolvedValue(false);
  vi.mocked(recordOtpFailure).mockResolvedValue({ locked: false });
  vi.mocked(consumeUsedToken).mockResolvedValue(true);
  vi.mocked(createMfaGrant).mockResolvedValue("grant-1");
  vi.mocked(tryConsumeBackupCode).mockResolvedValue(true);
});

describe("POST /api/auth/2fa/validate", () => {
  it("accepts a valid TOTP and mints the grant", async () => {
    const token = authenticator.generate(SECRET);
    vi.mocked(getAdminDb).mockReturnValue(fakeDb({ secret: SECRET, enabled: true }) as never);

    const res = await POST(req({ token }));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ success: true });
    expect(vi.mocked(clearOtpFailures)).toHaveBeenCalledWith("admin-1");
    expect(vi.mocked(createMfaGrant)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(setMfaGrantCookie)).toHaveBeenCalledTimes(1);
  });

  it("accepts a valid backup code and atomically consumes it", async () => {
    const { hashBackupCode } = await import("@/lib/security");
    const code = "ABCDEFGH";
    vi.mocked(getAdminDb).mockReturnValue(
      fakeDb({ secret: SECRET, enabled: true, backupCodeHashes: [hashBackupCode(code)] }) as never
    );

    const res = await POST(req({ token: code }));
    expect(res.status).toBe(200);
    expect(vi.mocked(tryConsumeBackupCode)).toHaveBeenCalledWith("admin-1", hashBackupCode(code));
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_backup_used", {
      uid: "admin-1",
      remaining: 0,
    });
  });

  it("rejects a backup code that was already consumed", async () => {
    const { hashBackupCode } = await import("@/lib/security");
    vi.mocked(tryConsumeBackupCode).mockResolvedValue(false);
    vi.mocked(getAdminDb).mockReturnValue(
      fakeDb({ secret: SECRET, enabled: true, backupCodeHashes: [hashBackupCode("ABCDEFGH")] }) as never
    );

    const res = await POST(req({ token: "ABCDEFGH" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(createMfaGrant)).not.toHaveBeenCalled();
  });

  it("rejects an invalid token with 400 and records the failure", async () => {
    vi.mocked(getAdminDb).mockReturnValue(
      fakeDb({ secret: SECRET, enabled: true, backupCodeHashes: [] }) as never
    );

    const res = await POST(req({ token: "000000" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(recordOtpFailure)).toHaveBeenCalledWith("admin-1");
  });

  it("refuses a replayed TOTP with 400", async () => {
    const token = authenticator.generate(SECRET);
    vi.mocked(consumeUsedToken).mockResolvedValue(false);
    vi.mocked(getAdminDb).mockReturnValue(fakeDb({ secret: SECRET, enabled: true }) as never);

    const res = await POST(req({ token }));
    expect(res.status).toBe(400);
    expect(vi.mocked(createMfaGrant)).not.toHaveBeenCalled();
  });

  it("returns 429 while OTP-locked", async () => {
    vi.mocked(isOtpLocked).mockResolvedValue(true);
    vi.mocked(getAdminDb).mockReturnValue(fakeDb({ secret: SECRET, enabled: true }) as never);

    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(429);
  });

  it("returns 400 when 2FA is not enabled", async () => {
    vi.mocked(getAdminDb).mockReturnValue(fakeDb(undefined) as never);
    expect((await POST(req({ token: "123456" }))).status).toBe(400);

    vi.mocked(getAdminDb).mockReturnValue(
      fakeDb({ secret: SECRET, enabled: false }) as never
    );
    expect((await POST(req({ token: authenticator.generate(SECRET) }))).status).toBe(400);
  });

  it("rejects a missing or non-string token with 400", async () => {
    vi.mocked(getAdminDb).mockReturnValue(fakeDb({ secret: SECRET, enabled: true }) as never);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ token: 42 }))).status).toBe(400);
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    const res = await POST(req({ token: "123456" }));
    expect(res.status).toBe(401);
  });
});
