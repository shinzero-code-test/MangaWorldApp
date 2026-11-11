import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "./route";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { logSecurityEvent } from "@/lib/security";
import { req, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// (5) settings — the settings-oriented view of the same Remote Config
// template: GET coerces string defaults back to boolean/number; PUT goes
// through the same injection gate and stamps valueType per value type.
vi.mock("@/lib/auth", () => ({
  requireRole: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminRemoteConfig: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    logSecurityEvent: vi.fn().mockResolvedValue(undefined),
  };
});

function fakeRc(template: Record<string, unknown>) {
  const published: unknown[] = [];
  const rc = {
    getTemplate: vi.fn().mockResolvedValue(template),
    publishTemplate: vi.fn().mockImplementation(async (t: unknown) => {
      published.push(t);
    }),
  };
  return { rc, published };
}

const TEMPLATE = {
  parameters: {
    enabled: { defaultValue: { value: "true" }, valueType: "BOOLEAN", description: "" },
    disabled: { defaultValue: { value: "false" }, valueType: "BOOLEAN", description: "" },
    limit: { defaultValue: { value: "5" }, valueType: "NUMBER", description: "" },
    ratio: { defaultValue: { value: "1.5" }, valueType: "NUMBER", description: "" },
    greeting: { defaultValue: { value: "hello" }, valueType: "STRING", description: "" },
    raw: { valueType: "STRING", description: "" }, // no defaultValue → skipped
  },
  etag: "etag-1",
};

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

describe("GET /api/settings", () => {
  it("coerces boolean and numeric defaults, skips unvalued params", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      settings: {
        enabled: true,
        disabled: false,
        limit: 5,
        ratio: 1.5,
        greeting: "hello",
      },
    });
  });

  it("maps etag conflicts to 409", async () => {
    const rc = { getTemplate: vi.fn().mockRejectedValue(new Error("version conflict")) };
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);
    expect((await GET()).status).toBe(409);
  });

  it("returns 401/403 without the role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});

describe("PUT /api/settings", () => {
  it("publishes typed settings and creates the parameters map when absent", async () => {
    const template = structuredClone(TEMPLATE);
    const { rc, published } = fakeRc(template);
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({ settings: { enabled: false, limit: 10, mode: "dark" } }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    const publishedTemplate = published[0] as {
      parameters: Record<string, { defaultValue: { value: string }; valueType: string }>;
    };
    expect(publishedTemplate.parameters.enabled).toEqual({ defaultValue: { value: "false" }, valueType: "BOOLEAN" });
    expect(publishedTemplate.parameters.limit).toEqual({ defaultValue: { value: "10" }, valueType: "NUMBER" });
    expect(publishedTemplate.parameters.mode).toEqual({ defaultValue: { value: "dark" }, valueType: "STRING" });
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("remote_config_publish", {
      by: "admin-1",
      keys: ["enabled", "limit", "mode"],
    });
  });

  it("creates a parameters map when the template has none", async () => {
    const { rc, published } = fakeRc({ etag: "etag-9" });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({ settings: { a: 1 } }));
    expect(res.status).toBe(200);
    const publishedTemplate = published[0] as { parameters: Record<string, unknown> };
    expect(publishedTemplate.parameters.a).toEqual({ defaultValue: { value: "1" }, valueType: "NUMBER" });
  });

  it("rejects injection attempts with 400", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ settings: { "a b": 1 } }))).status).toBe(400);
    expect((await PUT(req({ settings: { obj: { x: 1 } } }))).status).toBe(400);
    expect((await PUT(req({ settings: {} }))).status).toBe(400);
    expect((await PUT(req({}))).status).toBe(400);
    expect(rc.publishTemplate).not.toHaveBeenCalled();
  });

  it("rejects non-allow-listed source base URLs with 400 and never publishes", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ settings: { source_azora_base_url: "https://evil.example.com" } }))).status).toBe(400);
    expect((await PUT(req({ settings: { source_azora_base_url: "http://azorafly.com" } }))).status).toBe(400);
    expect((await PUT(req({ settings: { source_nope_base_url: "https://nope.com" } }))).status).toBe(400);
    // Allow-listed host publishes normally.
    expect((await PUT(req({ settings: { source_azora_base_url: "https://azorafly.com" } }))).status).toBe(200);
    expect(rc.publishTemplate).toHaveBeenCalledTimes(1);
  });

  it("gates the API-host overrides too (U-30 residual)", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ settings: { source_azora_api_host: "evil.example.com" } }))).status).toBe(400);
    expect((await PUT(req({ settings: { source_azora_api_host: "api.mangasid.com" } }))).status).toBe(400);
    expect((await PUT(req({ settings: { source_nope_api_host: "api.example.com" } }))).status).toBe(400);
    expect(rc.publishTemplate).not.toHaveBeenCalled();

    expect((await PUT(req({ settings: { source_mangasid_api_host: "api.mangasid.com" } }))).status).toBe(200);
  });

  it("returns 401/403 without the role", async () => {
    mockRoleUnauthorized();
    expect((await PUT(req({ settings: { a: 1 } }))).status).toBe(401);
    mockRoleForbidden();
    expect((await PUT(req({ settings: { a: 1 } }))).status).toBe(403);
  });
});
