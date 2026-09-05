import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";
import { bigQueryProjectId, listDatasetTables, runQuery } from "@/lib/bigquery";

export const dynamic = "force-dynamic";

const DATASET = "firebase_crashlytics";
// Package com.exapps.mangaworld → underscores + platform (per Firebase docs).
const PREFERRED_TABLE = "com_exapps_mangaworld_ANDROID";

function pickTable(tables: string[]): string | null {
  if (tables.includes(PREFERRED_TABLE)) return PREFERRED_TABLE;
  const android = tables.filter((t) => t.toUpperCase().endsWith("_ANDROID") && !t.toUpperCase().endsWith("_REALTIME"));
  if (android.length > 0) return [...android].sort()[0];
  const anyBatch = tables.filter((t) => !t.toUpperCase().endsWith("_REALTIME") && !t.startsWith("INFORMATION_SCHEMA"));
  return anyBatch.length > 0 ? [...anyBatch].sort()[0] : null;
}

export async function GET() {
  try {
    await requireRole("super-admin");

    const { tables, error: listError } = await listDatasetTables(DATASET);
    const table = pickTable(tables);
    if (!table) {
      return NextResponse.json({
        issues: [],
        stats: { crashFreeRate: null, crashFreeRateDelta: 0, totalIssues: 0, openIssues: 0, totalCrashes: 0, affectedUsers: 0 },
        bigquery: {
          available: false,
          reason: listError === "permission-denied"
            ? "permission-denied"
            : "no-exported-data",
          hint: listError === "permission-denied"
            ? "Service account needs BigQuery Data Viewer + Job User."
            : "BigQuery export is linked but no tables exist yet — data appears after the first daily export with crash events.",
        },
      });
    }

    const project = bigQueryProjectId();
    const result = await runQuery(
      `SELECT issue_id,
        (SELECT ANY_VALUE(title) FROM UNNEST(exceptions)) AS title,
        (SELECT ANY_VALUE(exception_message) FROM UNNEST(exceptions)) AS message,
        (SELECT ANY_VALUE(type) FROM UNNEST(exceptions)) AS exception_type,
        COUNT(*) AS events,
        SUM(CASE WHEN error_type = 'FATAL' THEN 1 ELSE 0 END) AS fatal,
        MAX(event_timestamp) AS last_seen,
        MIN(event_timestamp) AS first_seen,
        ANY_VALUE(application.display_version) AS app_version,
        COUNT(DISTINCT installation_uuid) AS devices
      FROM \`${project}.${DATASET}.${table}\`
      WHERE event_timestamp >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
      GROUP BY issue_id
      ORDER BY events DESC
      LIMIT 50`
    );
    if (!result.ok) {
      return NextResponse.json({
        issues: [],
        stats: { crashFreeRate: null, crashFreeRateDelta: 0, totalIssues: 0, openIssues: 0, totalCrashes: 0, affectedUsers: 0 },
        bigquery: { available: false, reason: result.error ?? "bq-query-error" },
      });
    }

    const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : Number(v) || 0);
    const issues = result.rows.map((r) => ({
      id: String(r.issue_id ?? ""),
      title: typeof r.title === "string" && r.title ? r.title : (typeof r.message === "string" && r.message ? r.message : "Unknown crash"),
      subtitle: typeof r.exception_type === "string" ? r.exception_type : "",
      state: "open",
      count: num(r.events),
      users: num(r.devices),
      fatal: num(r.fatal),
      firstOccurrence: typeof r.first_seen === "string" ? r.first_seen.slice(0, 19) : null,
      lastOccurrence: typeof r.last_seen === "string" ? r.last_seen.slice(0, 19) : null,
      appVersions: typeof r.app_version === "string" && r.app_version ? [r.app_version] : [],
      osVersions: [],
      devices: [],
    }));

    const totalCrashes = issues.reduce((sum, i) => sum + (i.count || 0), 0);
    return NextResponse.json({
      issues,
      stats: {
        crashFreeRate: null,
        crashFreeRateDelta: 0,
        totalIssues: issues.length,
        openIssues: issues.length,
        totalCrashes,
        affectedUsers: issues.reduce((sum, i) => sum + (i.users || 0), 0),
      },
      bigquery: { available: true, table },
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
