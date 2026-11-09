import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST, GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { logSecurityEvent } from "@/lib/security";
import { mockSessionUser, mockSessionStale, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/setup — enrollment on a FRESH session only; the TOTP secret
// is returned once and stored encrypted; pending secrets are reused, never
// rotated silently; enabled users get alreadyEnabled (no state change).
// otplib + qrcode run REAL (pure) — the only mocks are the session gate,
// Firestore, and the audit log. genericErrorResponse stays REAL.
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

type DocData = Record<string, unknown> | undefined;

function fakeDb(docData: DocData) {
  const sets: Array<{ path: string; data: Record<string, unknown> }> = [];
  const db = {
    collection: (_name: string) => ({
      doc: (id: string) => ({
        get: async () => ({
          exists: docData !== undefined,
          data: () => docData,
        }),
        set: async (data: Record<string, unknown>) => {
          sets.push({ path: `admin2fa/${id}`, data });
        },
      }),
    }),
  };
  return { db, sets };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSessionUser();
});

describe("POST /api/auth/2fa/setup", () => {
  it("issues a pending secret once and stores it encrypted", async () => {
    const { db, sets } = fakeDb(undefined);
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(200);
    const body = (await res.json()) as { secret: string; qrDataUrl: string };
    expect(body.secret).toMatch(/^[A-Z2-7=]+$/);
    expect(body.qrDataUrl).toMatch(/^data:image\/png;base64,/);
    expect(res.headers.get("Cache-Control")).toBe("no-store");

    expect(sets).toHaveLength(1);
    expect(sets[0].path).toBe("admin2fa/admin-1");
    expect(sets[0].data.secret).toBeNull(); // never raw at rest
    expect(sets[0].data.enabled).toBe(false);
    const enc = sets[0].data.secretEncrypted as { v: number; iv: string; tag: string; data: string };
    expect(enc.v).toBe(1);
    expect(enc.iv).toBeTruthy();
    expect(enc.tag).toBeTruthy();
    expect(enc.data).toBeTruthy();
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_pending_secret_issued", {
      uid: "admin-1",
    });
  });

  it("reuses an existing pending encrypted secret", async () => {
    const { encryptSecret } = await import("@/lib/security");
    const secret = "JBSWY3DPEHPK3PXP"; // base32, deterministic
    const { db, sets } = fakeDb({ secretEncrypted: encryptSecret(secret), enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(200);
    const body = (await res.json()) as { secret: string };
    expect(body.secret).toBe(secret);
    expect(sets).toHaveLength(0); // reuse must not rotate
  });

  it("reuses a legacy plaintext pending secret", async () => {
    const { db, sets } = fakeDb({ secret: "LEGACYSECRET1234", enabled: false });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(200);
    expect(((await res.json()) as { secret: string }).secret).toBe("LEGACYSECRET1234");
    expect(sets).toHaveLength(0);
  });

  it("reports alreadyEnabled without touching state", async () => {
    const { db, sets } = fakeDb({ enabled: true });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(200);
    expect(((await res.json()) as { alreadyEnabled: boolean }).alreadyEnabled).toBe(true);
    expect(sets).toHaveLength(0);
  });

  it("refuses a stale session with 403", async () => {
    mockSessionStale();
    const { db, sets } = fakeDb(undefined);
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST();
    expect(res.status).toBe(403);
    expect(sets).toHaveLength(0);
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("2fa_setup_stale_session", {
      uid: "admin-1",
    });
  });

  it("refuses a session without an authTime with 403", async () => {
    mockSessionUser({}, undefined as unknown as number);
    vi.mocked(getAdminDb).mockReturnValue(fakeDb(undefined).db as never);
    const res = await POST();
    expect(res.status).toBe(403);
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    const res = await POST();
    expect(res.status).toBe(401);
  });
});

describe("GET /api/auth/2fa/setup", () => {
  it("hard-rejects the legacy side-effecting GET shape with 405", async () => {
    const res = await GET();
    expect(res.status).toBe(405);
  });
});
