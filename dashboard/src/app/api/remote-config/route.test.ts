import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "./route";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { logSecurityEvent } from "@/lib/security";
import { req, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden, mockRoleMfaRequired } from "@/app/api/__tests__/route-harness";

// (5) remote-config — super-admin-only Remote Config template read/write
// behind the REAL validateRemoteConfigParams gate (key charset, primitive
// values, bounded size/count) + the U-30 source base-URL allow-list gate.
// The RC template is fetched fresh per write so its etag guards against
// lost-update conflicts; every publish is audit-logged (U-30).
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
    flag: { defaultValue: { value: "true" }, valueType: "BOOLEAN", description: "a flag" },
    limit: { defaultValue: { value: "5" }, valueType: "NUMBER", description: "" },
    greeting: { defaultValue: "hello", valueType: "STRING", description: "greet" },
  },
  conditions: { c1: {} },
  etag: "etag-1",
};

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

describe("GET /api/remote-config", () => {
  it("projects template parameters to display values", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = (await res.json()) as {
      parameters: Record<string, { defaultValue: string; valueType: string; description: string }>;
      template: { parameterCount: number; conditionCount: number; etag: string };
    };
    expect(body.parameters.flag).toEqual({ defaultValue: "true", valueType: "BOOLEAN", description: "a flag" });
    expect(body.parameters.limit).toEqual({ defaultValue: "5", valueType: "NUMBER", description: "" });
    expect(body.parameters.greeting).toEqual({ defaultValue: "hello", valueType: "STRING", description: "greet" });
    expect(body.template).toEqual({ parameterCount: 3, conditionCount: 1, etag: "etag-1" });
  });

  it("maps etag conflicts to 409", async () => {
    const rc = { getTemplate: vi.fn().mockRejectedValue(new Error("etag mismatch")) };
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(409);
  });

  it("returns 401/403 without the role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
    mockRoleMfaRequired();
    expect((await GET()).status).toBe(403);
  });
});

describe("PUT /api/remote-config", () => {
  it("publishes typed parameters onto the fetched template", async () => {
    const { rc, published } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({ parameters: { newFlag: true, limit: 42, name: "x" } }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, etag: "etag-1" });

    expect(rc.publishTemplate).toHaveBeenCalledTimes(1);
    const template = published[0] as {
      parameters: Record<string, { defaultValue: { value: string }; valueType: string }>;
    };
    expect(template.parameters.newFlag).toEqual({ defaultValue: { value: "true" }, valueType: "BOOLEAN" });
    expect(template.parameters.limit).toEqual({ defaultValue: { value: "42" }, valueType: "NUMBER" });
    expect(template.parameters.name).toEqual({ defaultValue: { value: "x" }, valueType: "STRING" });
    // Pre-existing parameters survive the merge.
    // Pre-existing parameters survive the merge.
    expect(template.parameters.flag.valueType).toBe("BOOLEAN");
    expect(vi.mocked(logSecurityEvent)).toHaveBeenCalledWith("remote_config_publish", {
      by: "admin-1",
      keys: ["limit", "name", "newFlag"],
    });
  });

  it("rejects config-injection attempts with 400", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ parameters: { "bad key!": true } }))).status).toBe(400);
    expect((await PUT(req({ parameters: { nested: { deep: true } } }))).status).toBe(400);
    expect((await PUT(req({ parameters: { big: "x".repeat(2001) } }))).status).toBe(400);
    expect((await PUT(req({ parameters: {} }))).status).toBe(400);
    expect((await PUT(req({}))).status).toBe(400);
    expect(rc.publishTemplate).not.toHaveBeenCalled();
  });

  it("accepts allow-listed source base URLs and publishes them", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({
      parameters: { source_azora_base_url: "https://azorafly.com", source_mangasid_base_url: "https://www.mangasid.com" },
    }));
    expect(res.status).toBe(200);
    expect(rc.publishTemplate).toHaveBeenCalledTimes(1);
  });

  it("rejects non-allow-listed source base URLs with 400 and never publishes", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    // Host belongs to a different source.
    expect((await PUT(req({ parameters: { source_azora_base_url: "https://starzmanga.com" } }))).status).toBe(400);
    // Arbitrary https host (the U-30 content-spoofing channel).
    expect((await PUT(req({ parameters: { source_azora_base_url: "https://evil.example.com" } }))).status).toBe(400);
    // Not https.
    expect((await PUT(req({ parameters: { source_azora_base_url: "http://azorafly.com" } }))).status).toBe(400);
    // Path/credentials are not origins.
    expect((await PUT(req({ parameters: { source_azora_base_url: "https://azorafly.com/series" } }))).status).toBe(400);
    expect((await PUT(req({ parameters: { source_azora_base_url: "https://user:pass@azorafly.com" } }))).status).toBe(400);
    // Unknown source id.
    expect((await PUT(req({ parameters: { source_nope_base_url: "https://nope.com" } }))).status).toBe(400);
    expect(rc.publishTemplate).not.toHaveBeenCalled();
  });

  it("gates the API-host overrides too (U-30 residual)", async () => {
    const { rc } = fakeRc(structuredClone(TEMPLATE));
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    // Arbitrary host — the same content-spoofing channel as the base URL.
    expect((await PUT(req({ parameters: { source_azora_api_host: "evil.example.com" } }))).status).toBe(400);
    // Another source's API host.
    expect((await PUT(req({ parameters: { source_azora_api_host: "api.mangasid.com" } }))).status).toBe(400);
    // Unknown id.
    expect((await PUT(req({ parameters: { source_nope_api_host: "api.example.com" } }))).status).toBe(400);
    expect(rc.publishTemplate).not.toHaveBeenCalled();

    // The allow-listed hosts publish normally.
    expect((await PUT(req({ parameters: { source_azora_api_host: "api.azorafly.com" } }))).status).toBe(200);
    expect((await PUT(req({ parameters: { source_meshmanga_api_host: "appswat.com" } }))).status).toBe(200);
  });

  it("returns 401/403 without the role", async () => {
    mockRoleUnauthorized();
    expect((await PUT(req({ parameters: { a: 1 } }))).status).toBe(401);
    mockRoleForbidden();
    expect((await PUT(req({ parameters: { a: 1 } }))).status).toBe(403);
  });

  it("surfaces publish failures as 500", async () => {
    const template = structuredClone(TEMPLATE);
    const rc = {
      getTemplate: vi.fn().mockResolvedValue(template),
      publishTemplate: vi.fn().mockRejectedValue(new Error("rc quota hunter2")),
    };
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({ parameters: { a: 1 } }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("hunter2");
  });
});
