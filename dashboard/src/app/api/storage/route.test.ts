import "../__tests__/env-cloudinary";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/storage (the Cloudinary usage read-out).
//
// Pinned contracts:
//  - super-admin gate (401/403)
//  - 500 when the Cloudinary credentials are not configured (never a
//    fabricated usage number)
//  - the usage envelope is shape-tolerant (the API has shipped both
//    {resources:{created_bytes}} and {storage:{usage}} shapes)
//  - a Cloudinary failure is a generic 500 without the upstream detail
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("GET /api/storage", () => {
  it("reports the Cloudinary usage", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ resources: { created_bytes: 2048, count: 3 } }),
      }),
    );

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.totalBytes).toBe(2048);
    expect(body.bucketName).toBe("test-cloud");
    expect(body.breakdown[0].fileCount).toBe(3);
  });

  it("accepts the alternative storage.usage envelope", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ storage: { usage: 512 } }),
      }),
    );

    const res = await GET();
    expect(res.status).toBe(200);
    expect((await res.json()).totalBytes).toBe(512);
  });

  it("returns 500 when the Cloudinary credentials are not configured", async () => {
    vi.stubEnv("CLOUDINARY_CLOUD_NAME", "");
    vi.stubEnv("CLOUDINARY_API_KEY", "");
    vi.stubEnv("CLOUDINARY_API_SECRET", "");
    vi.resetModules();
    const fresh = await import("./route");

    const res = await fresh.GET();
    expect(res.status).toBe(500);
  });

  it("surfaces a Cloudinary failure as a generic 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 401 }));

    const res = await GET();
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("401");
  });

  it("returns 401/403 without the super-admin role", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
