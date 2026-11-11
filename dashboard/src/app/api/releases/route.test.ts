import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/releases (the GitHub release feed).
//
// Pinned contracts:
//  - moderator gate (401/403)
//  - draft releases never reach the dashboard
//  - the version/build number are derived from the tag and the body, and
//    the notes are bounded (2000 chars)
//  - a GitHub outage answers 502 (not a silent empty list) and, once a
//    snapshot is cached, falls back to the stale snapshot with stale:true
//  - the 10-minute cache keeps the unauthenticated GitHub quota alive
//
// NOTE: the route caches module-level, so these cases run in order and
// share one cache — that is the behaviour under test.
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});

const GITHUB_RELEASE = {
  id: 42,
  tag_name: "v9.7.1",
  name: "Release 9.7.1",
  body: "notes\nbuild 278",
  published_at: "2026-01-01T00:00:00Z",
  draft: false,
  prerelease: false,
  assets: [{ download_count: 3 }, { download_count: 4 }],
};

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("GET /api/releases", () => {
  it("answers 502 when GitHub fails and nothing is cached yet", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 503 }));

    const res = await GET();
    expect(res.status).toBe(502);
  });

  it("maps the releases, drops drafts and aggregates the downloads", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          GITHUB_RELEASE,
          { id: 43, tag_name: "v9.8.0", draft: true, body: "" },
          { id: 44, tag_name: "v9.9.0", prerelease: true, body: "beta" },
        ],
      }),
    );

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.releases).toHaveLength(2); // the draft is dropped
    expect(body.releases[0]).toMatchObject({
      id: "42",
      version: "9.7.1",
      buildNumber: 278,
      platform: "android",
      status: "active",
      downloads: 7,
    });
    expect(body.releases[1].status).toBe("draft");
  });

  it("serves the cached snapshot while it is fresh (no GitHub call)", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.releases).toHaveLength(2);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("falls back to the stale snapshot when GitHub is down", async () => {
    // Past the 10-minute cache TTL the route calls GitHub again; on an
    // outage it answers with the stale snapshot and flags it.
    vi.useFakeTimers();
    vi.setSystemTime(Date.now() + 11 * 60 * 1000);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 503 }));

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.stale).toBe(true);
    expect(body.releases).toHaveLength(2);
    vi.useRealTimers();
  });

  it("returns 401/403 without the moderator role", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
