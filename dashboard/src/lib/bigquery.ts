import { getAccessToken } from "./firebase-admin";

const PROJECT_ID =
  process.env.FIREBASE_PROJECT_ID
  ?? process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID
  ?? "";

export interface BigQueryAvailability {
  available: boolean;
  dataset: string;
  tables: string[];
  reason?: string;
}

async function bqFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = await getAccessToken();
  return fetch(`https://bigquery.googleapis.com/bigquery/v2${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
}

/** Lists table IDs in a dataset. Empty array = linked but no exports yet. */
export async function listDatasetTables(dataset: string): Promise<{ tables: string[]; error?: string }> {
  if (!PROJECT_ID) return { tables: [], error: "missing-project" };
  try {
    const res = await bqFetch(
      `/projects/${PROJECT_ID}/datasets/${dataset}/tables?maxResults=50`
    );
    if (res.status === 403 || res.status === 401) return { tables: [], error: "permission-denied" };
    if (res.status === 404) return { tables: [], error: "dataset-missing" };
    if (!res.ok) return { tables: [], error: `bq-error-${res.status}` };
    const data = await res.json();
    const tables = Array.isArray(data.tables)
      ? data.tables.map((t: { tableReference?: { tableId?: string } }) => t.tableReference?.tableId ?? "").filter(Boolean)
      : [];
    return { tables };
  } catch {
    return { tables: [], error: "bq-unreachable" };
  }
}

export interface QueryResult {
  ok: boolean;
  rows: Record<string, unknown>[];
  error?: string;
}

/** Runs a Standard SQL query in the EU location (where Firebase exports live). */
export async function runQuery(
  sql: string,
  maxResults = 200
): Promise<QueryResult> {
  if (!PROJECT_ID) return { ok: false, rows: [], error: "missing-project" };
  try {
    const res = await bqFetch(`/projects/${PROJECT_ID}/queries`, {
      method: "POST",
      body: JSON.stringify({
        query: sql,
        location: "EU",
        useLegacySql: false,
        maxResults,
        timeoutMs: 25000,
      }),
    });
    if (res.status === 403 || res.status === 401) return { ok: false, rows: [], error: "permission-denied" };
    if (!res.ok) return { ok: false, rows: [], error: `bq-error-${res.status}` };
    const data = await res.json();
    if (data.errors?.length) return { ok: false, rows: [], error: "bq-query-error" };
    const fields: { name: string }[] = data.schema?.fields ?? [];
    const rows = Array.isArray(data.rows)
      ? data.rows.map((r: { f?: { v?: unknown }[] }) => {
          const row: Record<string, unknown> = {};
          (r.f ?? []).forEach((cell, i) => {
            row[fields[i]?.name ?? `f${i}`] = unwrap(cell?.v);
          });
          return row;
        })
      : [];
    return { ok: true, rows };
  } catch {
    return { ok: false, rows: [], error: "bq-unreachable" };
  }
}

function unwrap(value: unknown): unknown {
  // BigQuery REST wraps values: {v} cells, TIMESTAMP as epoch-seconds string,
  // nested/repeated as {f:[...]}/{v:[...]}. Flatten one level for UI use.
  if (value !== null && typeof value === "object") {
    if ("v" in (value as Record<string, unknown>)) {
      const inner = (value as Record<string, unknown>).v;
      if (Array.isArray(inner)) return inner.map(unwrap);
      return unwrap(inner);
    }
    if ("f" in (value as Record<string, unknown>)) {
      return ((value as Record<string, unknown>).f as unknown[]).map(unwrap);
    }
  }
  return value;
}

export function bigQueryProjectId(): string {
  return PROJECT_ID;
}
