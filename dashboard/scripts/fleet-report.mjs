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
 *
 * The credential is a service-account JSON whose `private_key` is a PEM block.
 * A PEM carries LITERAL newlines, so in a .env file the value spans many
 * lines: it must be read as a BLOCK (from `FIREBASE_SERVICE_ACCOUNT=` to the
 * end of the JSON object), never as a single line. A one-line read truncates
 * the JSON, `JSON.parse` throws, and the report dies before it ever reaches
 * GA4 — which is exactly what happened before this parser existed, and why
 * "the remaining blocker is ADOPTION" was unmeasurable.
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

/* ── credential parsing ─────────────────────────────────────────────────────
 *
 * The value is a service-account JSON whose `private_key` is a PEM block with
 * literal newlines. Three shapes have to be supported:
 *
 *   1. `process.env.FIREBASE_SERVICE_ACCOUNT` holding the whole JSON
 *      (single- or multi-line) — the usual CI shape.
 *   2. `process.env.FIREBASE_SERVICE_ACCOUNT` holding a quoted JSON blob.
 *   3. a .env file where the value starts after `FIREBASE_SERVICE_ACCOUNT=`
 *      and CONTINUES on the following lines until the JSON object closes.
 *
 * A PEM body line may end with `=` padding, so "the value ends at the next
 * KEY= line" is unsafe: a base64 line would be mistaken for an assignment and
 * the JSON truncated. An UNQUOTED value is therefore delimited by scanning
 * the JSON object itself (string-aware). A QUOTED value has no unambiguous
 * end when its inner quotes are unescaped, so every closing quote is tried
 * and the shortest span that parses as a service account wins.
 */

/**
 * Escapes the raw control characters (the PEM's literal newlines) that appear
 * INSIDE JSON string literals. `JSON.parse` rejects them, yet a .env file has
 * no other way to carry a multi-line PEM. Characters outside string literals
 * are left untouched, so the structure of the document is preserved.
 */
function escapeControlCharsInStrings(json) {
  let out = "";
  let inString = false;
  let escaped = false;
  for (const ch of json) {
    if (inString) {
      if (escaped) {
        out += ch;
        escaped = false;
        continue;
      }
      if (ch === "\\") {
        out += ch;
        escaped = true;
        continue;
      }
      if (ch === '"') {
        out += ch;
        inString = false;
        continue;
      }
      const code = ch.codePointAt(0);
      if (code < 0x20) {
        out +=
          ch === "\n" ? "\\n" : ch === "\r" ? "\\r" : ch === "\t" ? "\\t"
          : "\\u" + code.toString(16).padStart(4, "0");
        continue;
      }
      out += ch;
      continue;
    }
    if (ch === '"') {
      out += ch;
      inString = true;
      continue;
    }
    out += ch;
  }
  return out;
}

/**
 * Index just past the `}` that closes the JSON object starting at `start`,
 * or -1 when the text never balances. String-aware: a `}` inside the PEM or
 * a URL cannot end the object early.
 */
function endOfJsonObject(text, start) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
        continue;
      }
      if (ch === "\\") {
        escaped = true;
        continue;
      }
      if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return i + 1;
    }
  }
  return -1;
}

/**
 * The value of `key` in a .env-style text (a whole file, or a snippet), as a
 * BLOCK that may span lines. Returns null when the key is absent.
 *
 * An unquoted value ends where its JSON object closes. A quoted value is
 * returned from the opening quote to the end of the text: its closing quote
 * is not knowable here (inner quotes may be unescaped), and the candidate
 * ladder in `readings()` narrows it down.
 */
function extractBlock(text, key) {
  const re = new RegExp("^[ \\t]*(?:export[ \\t]+)?" + key + "[ \\t]*=", "gm");
  const m = re.exec(text);
  if (!m) return null;
  let i = m.index + m[0].length;
  while (i < text.length && (text[i] === " " || text[i] === "\t")) i++;
  const ch = text[i];
  if (ch === "{") {
    const end = endOfJsonObject(text, i);
    return end === -1 ? text.slice(i) : text.slice(i, end);
  }
  if (ch === '"' || ch === "'") return text.slice(i);
  // A plain scalar: the value runs to the end of the line.
  const nl = text.indexOf("\n", i);
  return nl === -1 ? text.slice(i) : text.slice(i, nl);
}

/** dotenv-style unescaping of a quoted value: `\"` → `"`, `\\` → `\`. */
function unescapeDotenv(inner) {
  return inner.replace(/\\(["\\])/g, "$1");
}

/**
 * Every plausible reading of a credential value, most specific first:
 *
 *   1. a JSON string literal wrapping the document (`JSON.parse` un-escapes it),
 *   2. the content of a quoted value, for every possible closing quote — the
 *      shortest span that parses is the real one,
 *   3. the value exactly as it stands.
 */
function readings(value) {
  const list = [];
  const add = (t) => {
    if (typeof t === "string" && t.trim()) list.push(t);
  };
  const trimmed = value.trim();

  if (trimmed.startsWith('"') || trimmed.startsWith("'")) {
    const quote = trimmed[0];
    // Bounded: a pathological file must not turn this into an O(n²) copy.
    // 500 spans is far above the ~40 a service-account JSON needs.
    let seen = 0;
    for (let j = 1; j < trimmed.length && seen < 500; j++) {
      if (trimmed[j] !== quote) continue;
      seen++;
      const inner = trimmed.slice(1, j);
      // A JSON string literal wrapping the document.
      if (quote === '"') {
        try {
          const unescaped = JSON.parse(trimmed.slice(0, j + 1));
          if (typeof unescaped === "string") add(unescaped);
        } catch {
          /* not a JSON string literal — the other readings still apply */
        }
      }
      // The document itself, quoted (dotenv) or unquoted.
      add(inner);
      add(unescapeDotenv(inner));
    }
  }
  add(trimmed);
  return list;
}

/** Where the credential comes from, and every candidate reading of it. */
function credentialCandidates() {
  const fromEnv = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (fromEnv && fromEnv.trim()) {
    const t = fromEnv.trim();
    if (t.startsWith("{") || t.startsWith('"') || t.startsWith("'")) {
      return readings(t).map((text) => ({ text, source: "process.env.FIREBASE_SERVICE_ACCOUNT" }));
    }
    // An env-file style snippet pasted into the variable.
    const block = extractBlock(fromEnv, "FIREBASE_SERVICE_ACCOUNT");
    return readings(block ?? t).map((text) => ({
      text,
      source: "process.env.FIREBASE_SERVICE_ACCOUNT",
    }));
  }
  const file = new URL("../.env.local", import.meta.url);
  let env;
  try {
    env = readFileSync(file, "utf8");
  } catch {
    throw new Error(
      "FIREBASE_SERVICE_ACCOUNT is not set in the environment and dashboard/.env.local could not be read. " +
        "Export the service-account JSON as FIREBASE_SERVICE_ACCOUNT."
    );
  }
  const block = extractBlock(env, "FIREBASE_SERVICE_ACCOUNT");
  if (!block) {
    throw new Error(
      "FIREBASE_SERVICE_ACCOUNT not found in dashboard/.env.local (and not set in the environment)."
    );
  }
  return readings(block).map((text) => ({ text, source: "dashboard/.env.local" }));
}

/**
 * `JSON.parse` errors embed a snippet of the input in newer V8 versions.
 * Never echo that — a credential is never printed in an error message.
 */
function safeJsonError(err) {
  const msg = String(err?.message ?? "");
  const pos = /position (\d+)/.exec(msg);
  return pos ? `unexpected input at position ${pos[1]}` : "unexpected input";
}

function serviceAccount() {
  const candidates = credentialCandidates();
  if (!candidates.length) throw new Error("FIREBASE_SERVICE_ACCOUNT is empty.");

  let last = null;
  for (const { text, source } of candidates) {
    const value = text.trim();
    const attempts = [value];
    const sanitized = escapeControlCharsInStrings(value);
    if (sanitized !== value) attempts.push(sanitized);
    for (const attempt of attempts) {
      let parsed;
      try {
        parsed = JSON.parse(attempt);
      } catch (err) {
        // The FIRST failure is kept: the candidates are ordered from the most
        // specific reading to the raw value, so it describes the value best.
        if (!last) last = { source, reason: safeJsonError(err) };
        continue;
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        if (!last) last = { source, reason: "not a JSON object" };
        continue;
      }
      if (!parsed.client_email || !parsed.private_key) {
        if (!last) last = { source, reason: 'missing "client_email"/"private_key"' };
        continue;
      }
      return parsed;
    }
  }

  const expected =
    ' Expected {"type":"service_account",...,"private_key":"-----BEGIN PRIVATE KEY-----\\n...\\n-----END PRIVATE KEY-----\\n"}.';
  const source = last?.source ?? "the environment";
  const reason = last?.reason ? ` (${last.reason})` : "";
  throw new Error(
    `FIREBASE_SERVICE_ACCOUNT (${source}) is not a valid service-account JSON` + reason + "." + expected
  );
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
// One line per entry: `table()` already ends with a newline, so it is
// normalised here — otherwise consecutive entries run together on one line.
const say = (s) => out.push(String(s).replace(/\n$/, "") + "\n");

async function main() {
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
}

try {
  await main();
} catch (err) {
  // A credential problem (or any other failure) must read as a sentence, not
  // as a stack trace — and must never echo key material.
  console.error(`fleet-report: ${err?.message ?? err}`);
  process.exit(1);
}
