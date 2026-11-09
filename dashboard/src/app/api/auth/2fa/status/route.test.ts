import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { mockSessionUser, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// (1) auth/2fa/status — the state projection the dashboard UI drives its
// setup/validate screens from. No fresh-auth requirement: a plain
// authenticated session must be able to read its own status.
process.env.MFA_SESSION_SECRET = "test-secret-for-vitest-only";

vi.mock("@/lib/auth", () => ({
  getCurrentUser: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return { ...actual };
});

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
});

describe("GET /api/auth/2fa/status", () => {
  it("reports an enabled+verified user as fully onboarded", async () => {
    mockSessionUser({ mfaVerified: true });
    vi.mocked(getAdminDb).mockReturnValue(
      fakeDb({ enabled: true, backupCodeHashes: ["a", "b", "c"] }) as never
    );

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      enabled: true,
      verified: true,
      needsSetup: false,
      needsValidation: false,
      backupCodesRemaining: 3,
    });
  });

  it("flags enabled-but-unverified users as needing validation", async () => {
    mockSessionUser({ mfaVerified: false });
    vi.mocked(getAdminDb).mockReturnValue(fakeDb({ enabled: true }) as never);

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({
      enabled: true,
      verified: false,
      needsSetup: false,
      needsValidation: true,
      backupCodesRemaining: 0,
    });
  });

  it("flags missing docs as needing setup", async () => {
    mockSessionUser({ mfaVerified: false });
    vi.mocked(getAdminDb).mockReturnValue(fakeDb(undefined) as never);

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({
      enabled: false,
      needsSetup: true,
      needsValidation: false,
    });
  });

  it("returns 401 when unauthenticated", async () => {
    mockSessionUnauthenticated();
    expect((await GET()).status).toBe(401);
  });
});
