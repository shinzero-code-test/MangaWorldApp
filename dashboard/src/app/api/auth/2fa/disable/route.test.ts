import { beforeEach, describe, expect, it, vi } from "vitest";
import { authenticator } from "otplib";
import { POST, GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  clearOtpFailures,
  consumeUsedToken,
  isOtpLocked,
  logSecurityEvent,
  recordOtpFailure,
  tryConsumeBackupCode,
} from "@/lib/security";
import { req, mockSessionUser, mockSessionStale, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/disable — self-service removal must prove possession (live
// TOTP or a single-use backup code) on a FRESH session, then wipe the seed,
// the hashes, and the lockout state. TOTP + backup hashing are REAL.
process.env.MFA_SESSION_SECRET = "test-secret-for-vitest-only";

vi.mock("@/lib/auth", () => ({
  getCurrentUser: vi.fn(),
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
  const sets: Array<Record<string, unknown>> = [];
  return {
    sets,
    db: {
      collection: (_name: string) => ({
        doc: (_id: string) => ({
          get: async () => ({ exists: docData !== undefined, data: () => docData }),
          set: async (data: Record<string, unknown>) => {
            sets.push(data);
          },
        }),
      }),
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSessionUser();
  vi.mocked(isOtpLocked).mockResolvedValue(false);
  vi.mocked(recordOtpFailure).mockResolvedValue({ locked: false });
  vi.mocked(consumeUsedToken).mockResolvedValue(true);
  vi.mocked(tryConsumeBackupCode).mockResolvedValue(true);
});

describe("POST /api/auth/2fa/disable", () => {
  it("disables 2FA on a valid TOTP proof and wipes the seed", async () => {
    const { db, sets } = fakeDb({ secret: SECRET, enabled: true, backupCodeHashes: ["h1"] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const proof = authenticator.generate(SECRET);

    const res = await POST(req({ proof }));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ success: true });
    expect(sets).toHaveLength(1);
    expect(sets[0]).toMatchObject({
      enabled: false,
      secret: null,
      secretEncrypted: null,
      backupCodeHashes: [],
      disabledViaBackup: false,
    });
    expect(typeof sets[0].disabledAt).toBe("number");
    expect(vi.mocked(clearOtpFailures)).toHaveBeenCalledWith("admin-1");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_disabled", {
      uid: "admin-1",
      viaBackup: false,
    });
  });

  it("disables on a backup-code proof and consumes it atomically", async () => {
    const { hashBackupCode } = await import("@/lib/security");
    const code = "ABCDEFGH";
    const { db, sets } = fakeDb({
      secret: SECRET,
      enabled: true,
      backupCodeHashes: [hashBackupCode(code)],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ proof: code }));
    expect(res.status).toBe(200);
    expect(vi.mocked(tryConsumeBackupCode)).toHaveBeenCalledWith("admin-1", hashBackupCode(code));
    expect(sets[0].disabledViaBackup).toBe(true);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_disabled", {
      uid: "admin-1",
      viaBackup: true,
    });
  });

  it("rejects an invalid proof with 400", async () => {
    const { db, sets } = fakeDb({ secret: SECRET, enabled: true, backupCodeHashes: [] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ proof: "000000" }));
    expect(res.status).toBe(400);
    expect(sets).toHaveLength(0);
    expect(vi.mocked(recordOtpFailure)).toHaveBeenCalledWith("admin-1");
  });

  it("rejects an empty proof with 400", async () => {
    const { db } = fakeDb({ secret: SECRET, enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ proof: "" }))).status).toBe(400);
  });

  it("refuses a stale session with 403", async () => {
    mockSessionStale();
    const { db } = fakeDb({ secret: SECRET, enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ proof: "123456" }));
    expect(res.status).toBe(403);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_disable_stale_session", {
      uid: "admin-1",
    });
  });

  it("returns 429 while OTP-locked", async () => {
    vi.mocked(isOtpLocked).mockResolvedValue(true);
    const { db } = fakeDb({ secret: SECRET, enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ proof: "123456" }));
    expect(res.status).toBe(429);
  });

  it("returns 400 when 2FA is not enabled", async () => {
    const { db } = fakeDb({ secret: SECRET, enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ proof: authenticator.generate(SECRET) }));
    expect(res.status).toBe(400);
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    const res = await POST(req({ proof: "123456" }));
    expect(res.status).toBe(401);
  });
});

describe("GET /api/auth/2fa/disable", () => {
  it("hard-rejects with 405", async () => {
    const res = await GET();
    expect(res.status).toBe(405);
  });
});
