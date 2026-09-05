import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";
import { bigQueryProjectId, listDatasetTables, runQuery } from "@/lib/bigquery";

export const dynamic = "force-dynamic";

const DATASET = "firebase_performance";

function pickTable(tables: string[]): string | null {
  const usable = tables.filter((t) => !t.toUpperCase().startsWith("INFORMATION_SCHEMA"));
  if (usable.length === 0) return null;
  const android = usable.filter((t) => t.toLowerCase().includes("android"));
  return android.length > 0 ? [...android].sort()[0] : [...usable].sort()[0];
}

export async function GET() {
  try {
    await requireRole("super-admin");

    const { tables, error: listError } = await listDatasetTables(DATASET);
    const table = pickTable(tables);
    if (!table) {
      return NextResponse.json({
        summary: { avgStartup: 0, avgNetwork: 0, avgRender: 0, totalTraces: 0 },
        traces: [],
        screenMetrics: [],
        bigquery: {
          available: false,
          reason: listError === "permission-denied"
            ? "permission-denied"
            : "no-exported-data",
          hint: listError === "permission-denied"
            ? "Service account needs BigQuery Data Viewer + Job User."
            : "BigQuery export is linked but no tables exist yet — data appears after the first daily export with performance events.",
        },
      });
    }

    const project = bigQueryProjectId();
    const result = await runQuery(
      `SELECT event_type, event_name, COUNT(*) AS n,
        AVG(trace_info.duration_us) / 1000 AS avg_ms,
        APPROX_QUANTILES(trace_info.duration_us, 100)[OFFSET(50)] / 1000 AS p50_ms
      FROM \`${project}.${DATASET}.${table}\`
      WHERE event_timestamp >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)
      GROUP BY event_type, event_name
      ORDER BY n DESC
      LIMIT 100`
    );
    if (!result.ok) {
      return NextResponse.json({
        summary: { avgStartup: 0, avgNetwork: 0, avgRender: 0, totalTraces: 0 },
        traces: [],
        screenMetrics: [],
        bigquery: { available: false, reason: result.error ?? "bq-query-error" },
      });
    }

    const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : 0);
    const traces = result.rows.map((r) => ({
      name: String(r.event_name ?? "unknown"),
      eventType: String(r.event_type ?? ""),
      count: num(r.n),
      avgMs: Math.round(num(r.avg_ms)),
      p50Ms: Math.round(num(r.p50_ms)),
    }));

    const avgFor = (match: (name: string, type: string) => boolean): number => {
      const hit = traces.filter((t) => match(t.name, t.eventType));
      const total = hit.reduce((s, t) => s + t.count, 0);
      if (total === 0) return 0;
      return Math.round(hit.reduce((s, t) => s + t.avgMs * t.count, 0) / total);
    };
    const avgStartup = avgFor((n, t) => t === "DURATION_TRACE" && /app_start|startup|foreground/i.test(n));
    const avgNetwork = avgFor((_, t) => t === "NETWORK_REQUEST");
    const avgRender = avgFor((_, t) => t === "SCREEN_TRACE");

    return NextResponse.json({
      summary: {
        avgStartup,
        avgNetwork,
        avgRender,
        totalTraces: traces.reduce((s, t) => s + t.count, 0),
      },
      traces: traces.slice(0, 20),
      screenMetrics: [],
      bigquery: { available: true, table },
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
