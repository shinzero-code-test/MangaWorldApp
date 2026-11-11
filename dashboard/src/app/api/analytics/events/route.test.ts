import "../../__tests__/env-ga4";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAccessToken } from "@/lib/firebase-admin";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/analytics/events (the GA4 event read-out).
//
// Pinned contracts:
//  - moderator gate (401/403)
//  - GA4_PROPERTY_ID unset → the route says so explicitly instead of
//    silently answering with an empty list
//  - the Data API is called with the `analytics.readonly` scope (the
//    shared helper's cloud-platform default is rejected by Google)
//  - a 403 from Google is reported as `permission-denied`, other statuses
//    as `ga4-error-<status>`, and a transport failure as `ga4-unreachable`
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAccessToken: vi.fn(),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
  vi.mocked(getAccessToken).mockResolvedValue("ga4-token");
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("GET /api/analytics/events", () => {
  it("maps the GA4 rows into events", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        rows: [
          { dimensionValues: [{ value: "plugin_sync_outcome" }], metricValues: [{ value: "42" }] },
          { dimensionValues: [{ value: "plugin_sweep" }], metricValues: [{ value: "7" }] },
        ],
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.ga4).toEqual({ configured: true, accessible: true, error: null });
    expect(body.events).toHaveLength(2);
    expect(body.events[0]).toMatchObject({ name: "plugin_sync_outcome", count: 42 });
    expect(body.totalEvents).toBe(49);
    expect(body.topEvents[0]).toEqual({ name: "plugin_sync_outcome", count: 42 });
    // The Data API needs the analytics.readonly scope.
    expect(vi.mocked(getAccessToken)).toHaveBeenCalledWith(
      "https://www.googleapis.com/auth/analytics.readonly",
    );
  });

  it("reports a Google 403 as permission-denied", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 403 }));

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.ga4.accessible).toBe(false);
    expect(body.ga4.error).toBe("permission-denied");
    expect(body.events).toEqual([]);
  });

  it("reports any other Google status and a transport failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    expect((await (await GET()).json()).ga4.error).toBe("ga4-error-500");

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    expect((await (await GET()).json()).ga4.error).toBe("ga4-unreachable");
  });

  it("says so when GA4 is not configured", async () => {
    vi.stubEnv("GA4_PROPERTY_ID", "");
    vi.resetModules();
    const fresh = await import("./route");

    const res = await fresh.GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.ga4).toEqual({ configured: false, accessible: false });
    expect(body.note).toContain("GA4_PROPERTY_ID");
  });

  it("returns 401/403 without the moderator role", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ rows: [] }) }));
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
