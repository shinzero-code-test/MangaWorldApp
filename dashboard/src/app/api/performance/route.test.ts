import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { listDatasetTables, runQuery } from "@/lib/bigquery";
import { mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/performance (the BigQuery performance read-out).
//
// Pinned contracts:
//  - super-admin gate (401/403)
//  - no exported table → the "bigquery unavailable" shape with a hint
//  - the startup/network/render averages are derived from the trace
//    categories (DURATION_TRACE+startup, NETWORK_REQUEST, SCREEN_TRACE)
//    and are count-weighted, not a plain mean of means
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

describe("GET /api/performance", () => {
  it("reports 'bigquery unavailable' with a hint when no table is exported", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: [], error: "no-data" });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.bigquery.available).toBe(false);
    expect(body.bigquery.reason).toBe("no-exported-data");
    expect(body.bigquery.hint).toBeTruthy();
    expect(body.traces).toEqual([]);
  });

  it("derives the count-weighted averages from the traces", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: ["app_ANDROID_20260101"] });
    vi.mocked(runQuery).mockResolvedValue({
      ok: true,
      rows: [
        { event_name: "app_start", event_type: "DURATION_TRACE", n: 10, avg_ms: 100, p50_ms: 90 },
        { event_name: "fetch", event_type: "NETWORK_REQUEST", n: 30, avg_ms: 200, p50_ms: 180 },
        { event_name: "home", event_type: "SCREEN_TRACE", n: 20, avg_ms: 50, p50_ms: 40 },
      ],
    } as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.summary.avgStartup).toBe(100);
    expect(body.summary.avgNetwork).toBe(200);
    expect(body.summary.avgRender).toBe(50);
    expect(body.summary.totalTraces).toBe(60);
    expect(body.traces).toHaveLength(3);
    expect(body.bigquery.available).toBe(true);
  });

  it("reports 'bigquery unavailable' when the query fails", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: ["app_ANDROID_20260101"] });
    vi.mocked(runQuery).mockResolvedValue({ ok: false, error: "bq-quota", rows: [] } as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.bigquery.available).toBe(false);
    expect(body.bigquery.reason).toBe("bq-quota");
  });

  it("returns 401/403 without the super-admin role", async () => {
    vi.mocked(listDatasetTables).mockResolvedValue({ tables: [] });
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});
