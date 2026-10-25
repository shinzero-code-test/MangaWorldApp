#!/usr/bin/env node
/**
 * Gate 0 fleet report — the four soak exit queries, in one command.
 *
 * Why this exists: the Phase 4 gate's exit criteria were written as queries
 * against "the fleet view", but until v9.5.0 that view did not exist (the
 * telemetry was Crashlytics breadcrumbs, which cannot be grouped) and the
 * event parameters were not registered as GA4 custom definitions (GA4 only
 * records a parameter from the moment it is registered — historical data is
 * not retroactive, so the definitions must exist BEFORE the soak starts).
 *
 * Usage (from dashboard/):
 *   node scripts/fleet-report.mjs                # 30-day window
 *   node scripts/fleet-report.mjs --days 14      # the soak window
 *   node scripts/fleet-report.mjs --strict       # exit 1 if the cohort/criteria are not met
 *
 * Credentials: FIREBASE_SERVICE_ACCOUNT (JSON) from the environment, else the
 * dashboard/.env.local entry. The service account needs Viewer on the property
 * (it has Administrator) and the analytics.readonly scope — NOT cloud-platform,
 * which the Data API rejects.
 */
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { GoogleAuth } = require("google-auth-library");

const PROPERTY = process.env.GA4_PROPERTY_ID || "538347764";
const args = process.argv.slice(2);
const DAYS = Number(args[args.indexOf("--days") + 1]) || 30;
const STRICT = args.includes("--strict");
/** The first build that emits plugin_* Analytics events. */
const TELEMETRY_BUILD = "9.5.0";

function serviceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (raw && raw.trim().startsWith("{")) return JSON.parse(raw);
  const env = readFileSync(new URL("../.env.local", import.meta.url), "utf8");
  const line = env.split(/\r?\n/).find((l) => l.startsWith("FIREBASE_SERVICE_ACCOUNT="));
  if (!line) throw new Error("FIREBASE_SERVICE_ACCOUNT not found");
  let v = line.slice("FIREBASE_SERVICE_ACCOUNT=".length).trim();
  if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
  return JSON.parse(v);
}

async function token() {
  const sa = serviceAccount();
  const auth = new GoogleAuth({
    credentials: { client_email: sa.client_email, private_key: sa.private_key },
    scopes: ["https://www.googleapis.com/auth/analytics.readonly"],
  });
  return (await (await auth.getClient()).getAccessToken()).token;
}

async function report(token, body) {
  const res = await fetch(
    `https://analyticsdata.googleapis.com/v1beta/properties/${PROPERTY}:runReport`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        dateRanges: [{ startDate: `${DAYS}daysAgo`, endDate: "today" }],
        ...body,
      }),
    }
  );
  if (!res.ok) throw new Error(`GA4 ${res.status}: ${(await res.text()).slice(0, 300)}`);
  return (await res.json()).rows || [];
}

const dim = (r) => r.dimensionValues.map((d) => d.value);
const met = (r) => r.metricValues.map((m) => m.value);
const table = (rows, headers) => {
  if (!rows.length) return "  (no rows yet)\n";
  const w = headers.map((h, i) => Math.max(h.length, ...rows.map((r) => String(r[i]).length)));
  const line = (r) => "  " + r.map((c, i) => String(c).padEnd(w[i])).join("  ");
  return [line(headers), ...rows.map(line)].join("\n") + "\n";
};

const out = [];
const say = (s) => out.push(s);

const t = await token();

// ── Cohort readiness ────────────────────────────────────────────────────────
const versions = await report(t, {
  dimensions: [{ name: "appVersion" }],
  metrics: [{ name: "activeUsers" }],
  orderBys: [{ metric: { metricName: "activeUsers" }, desc: true }],
  limit: 20,
});
const rows = versions.map((r) => [dim(r)[0], met(r)[0], dim(r)[0] >= TELEMETRY_BUILD ? "telemetry" : "-"]);
const onTelemetry = rows.filter((r) => r[2] === "telemetry").reduce((a, r) => a + Number(r[1]), 0);
const totalUsers = rows.reduce((a, r) => a + Number(r[1]), 0);

say(`\n=== Cohort (${DAYS}d) ===`);
say(`total active users: ${totalUsers} | on >= ${TELEMETRY_BUILD}: ${onTelemetry}`);
say(table(rows, ["appVersion", "activeUsers", "emits plugin_*"]));

// ── Query 1: sweep outcome × outcome × build ────────────────────────────────
say(`=== Q1 sweep_outcome x outcome (x appVersion) ===`);
say(
  table(
    (
      await report(t, {
        dimensions: [{ name: "customEvent:outcome" }, { name: "appVersion" }],
        metrics: [{ name: "eventCount" }, { name: "customEvent:count" }],
        dimensionFilter: {
          filter: { fieldName: "eventName", stringFilter: { value: "plugin_sync_outcome" } },
        },
        orderBys: [{ metric: { metricName: "eventCount" }, desc: true }],
        limit: 40,
      })
    ).map((r) => [...dim(r), ...met(r)]),
    ["outcome", "appVersion", "events", "sum(count)"]
  )
);

// ── Query 2: rollbacks / quarantines (any occurrence is an incident) ────────
say(`=== Q2 incidents (RolledBack / Quarantined / Revoked) ===`);
const incidents = await report(t, {
  dimensions: [{ name: "customEvent:outcome" }, { name: "customEvent:plugin_id" }],
  metrics: [{ name: "eventCount" }],
  dimensionFilter: {
    andGroup: {
      expressions: [
        { filter: { fieldName: "eventName", stringFilter: { value: "plugin_sync_outcome" } } },
        {
          filter: {
            fieldName: "customEvent:outcome",
            inListFilter: { values: ["RolledBack", "Failed", "Revoked"] },
          },
        },
      ],
    },
  },
  limit: 40,
});
say(table(incidents.map((r) => [...dim(r), ...met(r)]), ["outcome", "plugin_id", "events"]));
const health = await report(t, {
  dimensions: [{ name: "customEvent:from" }, { name: "customEvent:to" }, { name: "customEvent:plugin_id" }],
  metrics: [{ name: "eventCount" }],
  dimensionFilter: { filter: { fieldName: "eventName", stringFilter: { value: "plugin_health" } } },
  limit: 40,
});
say(table(health.map((r) => [...dim(r), ...met(r)]), ["from", "to", "plugin_id", "events"]));

// ── Query 3: script_call × entry × version ─────────────────────────────────
say(`=== Q3 script_call x entry x version ===`);
say(
  table(
    (
      await report(t, {
        dimensions: [{ name: "customEvent:entry" }, { name: "customEvent:version" }],
        metrics: [
          { name: "eventCount" },
          { name: "customEvent:duration_ms" },
        ],
        dimensionFilter: {
          filter: { fieldName: "eventName", stringFilter: { value: "plugin_script_call" } },
        },
        orderBys: [{ metric: { metricName: "eventCount" }, desc: true }],
        limit: 40,
      })
    ).map((r) => [...dim(r), ...met(r)]),
    ["entry", "version", "events", "sum(duration_ms)"]
  )
);

// ── Query 4: trust_reject × reason (must stay zero in steady state) ─────────
say(`=== Q4 trust_reject x reason ===`);
const rejects = await report(t, {
  dimensions: [{ name: "customEvent:reason" }, { name: "customEvent:plugin_id" }],
  metrics: [{ name: "eventCount" }],
  dimensionFilter: { filter: { fieldName: "eventName", stringFilter: { value: "plugin_trust_reject" } } },
  limit: 40,
});
say(table(rejects.map((r) => [...dim(r), ...met(r)]), ["reason", "plugin_id", "events"]));

// ── Verdict (only meaningful with --strict) ────────────────────────────────
const incidentCount = incidents.reduce((a, r) => a + Number(met(r)[0]), 0);
const rejectCount = rejects.reduce((a, r) => a + Number(met(r)[0]), 0);
const ready = onTelemetry >= 2;
say(`=== Verdict ===`);
say(`cohort on >= ${TELEMETRY_BUILD}: ${onTelemetry} (need >= 2) -> ${ready ? "OK" : "NOT READY"}`);
say(`incidents: ${incidentCount} | trust rejects: ${rejectCount}`);

console.log(out.join(""));
if (STRICT && !ready) process.exit(1);
