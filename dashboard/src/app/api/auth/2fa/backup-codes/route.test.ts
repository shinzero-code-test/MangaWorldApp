import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST, GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { logSecurityEvent } from "@/lib/security";
import { mockSessionUser, mockSessionStale, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/backup-codes — recovery-code minting on a FRESH session only,
// plaintext shown once (no-store), hashes persisted. generateBackupCodes
// runs REAL (10 unique codes from the unambiguous alphabet).
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
  };
});

function fakeDb(docData: Record<string, unknown> | undefined) {
  const sets: Array<{ data: Record<string, unknown>; options: unknown }> = [];
  return {
    sets,
    db: {
      collection: (_name: string) => ({
        doc: (_id: string) => ({
          get: async () => ({ exists: docData !== undefined, data: () => docData }),
          set: async (data: Record<string, unknown>, options: unknown) => {
            sets.push({ data, options });
          },
        }),
      }),
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSessionUser();
});

describe("POST /api/auth/2fa/backup-codes", () => {
  it("issues 10 single-use codes, persists hashes, and marks no-store", async () => {
    const { db, sets } = fakeDb({ enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(200);
    const body = (await res.json()) as { codes: string[] };
    expect(body.codes).toHaveLength(10);
    expect(new Set(body.codes).size).toBe(10);
    for (const code of body.codes) {
      expect(code).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/);
    }
    expect(res.headers.get("Cache-Control")).toBe("no-store");

    expect(sets).toHaveLength(1);
    expect(sets[0].options).toEqual({ merge: true });
    const hashes = sets[0].data.backupCodeHashes as string[];
    expect(hashes).toHaveLength(10);
    expect(typeof sets[0].data.backupCodesAt).toBe("number");
    // A stored hash must never equal any issued plaintext code.
    for (const code of body.codes) expect(hashes).not.toContain(code);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_backup_issued", {
      uid: "admin-1",
      count: 10,
    });
  });

  it("refuses a stale session with 403", async () => {
    mockSessionStale();
    const { db, sets } = fakeDb({ enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(403);
    expect(sets).toHaveLength(0);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_backup_stale_session", {
      uid: "admin-1",
    });
  });

  it("returns 400 when 2FA is not enabled", async () => {
    const { db } = fakeDb({ enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST()).status).toBe(400);
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    expect((await POST()).status).toBe(401);
  });
});

describe("GET /api/auth/2fa/backup-codes", () => {
  it("hard-rejects with 405", async () => {
    const res = await GET();
    expect(res.status).toBe(405);
  });
});
