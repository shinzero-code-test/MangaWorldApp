import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "./route";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { req, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/moderation/banned-keywords (the RC keyword list the
// app and /api/community/moderate read).
//
// Pinned contracts:
//  - super-admin gate on both verbs (401/403)
//  - GET reads the `community_banned_keywords` RC parameter (the app's and
//    the community/moderate scanner's single source of truth)
//  - PUT publishes the list back to the RC template as a STRING parameter
//  - the keyword payload is bounded (a 10k+ payload is refused)
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminRemoteConfig: vi.fn(),
}));

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

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

describe("GET /api/moderation/banned-keywords", () => {
  it("returns the RC keyword list", async () => {
    const { rc } = fakeRc({
      parameters: {
        community_banned_keywords: { defaultValue: { value: "spam, scam" } },
      },
    });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ keywords: "spam, scam" });
  });

  it("returns an empty list when the parameter is absent", async () => {
    const { rc } = fakeRc({ parameters: {} });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ keywords: "" });
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});

describe("PUT /api/moderation/banned-keywords", () => {
  it("publishes the keyword list to the RC template", async () => {
    const { rc, published } = fakeRc({ parameters: {} });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await PUT(req({ keywords: "spam, scam" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    const template = published[0] as {
      parameters: Record<string, { defaultValue: { value: string }; valueType: string }>;
    };
    expect(template.parameters.community_banned_keywords).toEqual({
      defaultValue: { value: "spam, scam" },
      valueType: "STRING",
    });
  });

  it("clears the list with an empty string", async () => {
    const { rc, published } = fakeRc({ parameters: {} });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ keywords: "" }))).status).toBe(200);
    const template = published[0] as {
      parameters: Record<string, { defaultValue: { value: string } }>;
    };
    expect(template.parameters.community_banned_keywords.defaultValue.value).toBe("");
  });

  it("rejects a non-string or oversized keyword payload with 400 and never publishes", async () => {
    const { rc, published } = fakeRc({ parameters: {} });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    expect((await PUT(req({ keywords: 42 }))).status).toBe(400);
    expect((await PUT(req({ keywords: "x".repeat(10_001) }))).status).toBe(400);
    expect((await PUT(req({}))).status).toBe(400);
    expect(published).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    const { rc, published } = fakeRc({ parameters: {} });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    mockRoleUnauthorized();
    expect((await PUT(req({ keywords: "spam" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await PUT(req({ keywords: "spam" }))).status).toBe(403);
    expect(published).toHaveLength(0);
  });
});
