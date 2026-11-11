import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { listDatasetTables, runQuery } from "@/lib/bigquery";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/crashlytics (the BigQuery crash read-out).
//
// Pinned contracts:
//  - super-admin gate (401/403)
//  - no exported table → a "bigquery unavailable" answer with the reason
//    and an actionable hint (never a fabricated crash-free rate)
//  - a query failure → the same "unavailable" shape
//  - a successful query → the issues are mapped (id/title/count/users/fatal)
//    and the totals are aggregated
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/bigquery", () => ({
  bigQueryProjectId: vi.fn(() => "proj"),
  listDatasetTables: vi.fn(),
  pickLatestDatedTable: vi.fn((tables: string[]) => tables[tables.length - 1] ?? null),
  runQuery: vi.fn(),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
});

describe("GET /api/crashlytics", () => {
  it("reports 'bigquery unavailable' with a hint when no table is exported", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: [], error: "permission-denied" });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.bigquery.available).toBe(false);
    expect(body.bigquery.reason).toBe("permission-denied");
    expect(body.bigquery.hint).toBeTruthy();
    expect(body.issues).toEqual([]);
    expect(body.stats.crashFreeRate).toBeNull();
  });

  it("maps the issues and aggregates the totals", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({
      tables: ["com_exapps_mangaworld_ANDROID"],
    });
    vi.mocked(runQuery).mockResolvedValue({
      ok: true,
      rows: [
        {
          issue_id: "crash-1",
          title: "NullPointerException",
          exception_type: "FATAL",
          events: 12,
          fatal: 12,
          devices: 5,
          first_seen: "2026-01-01T00:00:00.000Z",
          last_seen: "2026-01-02T00:00:00.000Z",
          app_version: "9.7.1",
        },
      ],
    } as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.bigquery.available).toBe(true);
    expect(body.issues).toHaveLength(1);
    expect(body.issues[0]).toMatchObject({
      id: "crash-1",
      title: "NullPointerException",
      count: 12,
      users: 5,
      fatal: 12,
    });
    expect(body.stats.totalIssues).toBe(1);
    expect(body.stats.totalCrashes).toBe(12);
    expect(body.stats.affectedUsers).toBe(5);
  });

  it("reports 'bigquery unavailable' when the query fails", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: ["com_exapps_mangaworld_ANDROID"] });
    vi.mocked(runQuery).mockResolvedValue({ ok: false, error: "bq-quota", rows: [] } as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.bigquery.available).toBe(false);
    expect(body.bigquery.reason).toBe("bq-quota");
    expect(body.issues).toEqual([]);
  });

  it("returns 401/403 without the super-admin role", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: [] });
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
