// Publish / read the Remote Config update-gate values with the Firebase
// service account from dashboard/.env.local.
//
// The credential in .env.local is a service-account JSON whose PEM contains
// RAW newlines, so it is not valid JSON as it stands — the same multi-line
// PEM problem the fleet report had. This script repairs it the same way
// (escape raw newlines inside string literals) and then uses the JWT flow.
//
//   node scripts/rc-gate.mjs get
//   node scripts/rc-gate.mjs set <min_supported_version> [latest_version]
//
// Safety: the PUT body is built explicitly from {parameters, conditions} (the
// GET response also carries an output-only `version` object, which the API
// rejects if it is echoed back), the ETag comes from the response HEADER (it
// is not a body field), and every write is validated with validate_only
// BEFORE it is persisted.
import { readFileSync } from "node:fs";
import { createSign } from "node:crypto";

const ENV = new URL("../.env.local", import.meta.url);
const PROJECT = "mangaworld-live-260519";
const API = `https://firebaseremoteconfig.googleapis.com/v1/projects/${PROJECT}/remoteConfig`;

function extractBlock(text, key) {
  const re = new RegExp("^[ \\t]*(?:export[ \\t]+)?" + key + "[ \\t]*=", "gm");
  const m = re.exec(text);
  if (!m) return null;
  let i = m.index + m[0].length;
  while (i < text.length && (text[i] === " " || text[i] === "\t")) i++;
  if (text[i] !== "{") return null;
  let depth = 0, inStr = false, esc = false;
  for (let j = i; j < text.length; j++) {
    const c = text[j];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return text.slice(i, j + 1);
    }
  }
  return text.slice(i);
}

/** Escape the raw newlines that live inside JSON string literals. */
function repairJson(text) {
  let out = "", inStr = false, esc = false;
  for (const c of text) {
    if (inStr) {
      if (esc) { esc = false; out += c; continue; }
      if (c === "\\") { esc = true; out += c; continue; }
      if (c === '"') { inStr = false; out += c; continue; }
      if (c === "\n") { out += "\\n"; continue; }
      if (c === "\r") { continue; }
      out += c; continue;
    }
    if (c === '"') inStr = true;
    out += c;
  }
  return out;
}

function b64url(buf) {
  return Buffer.from(buf).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function accessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.remoteconfig",
    aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  }));
  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const jwt = `${header}.${claims}.${b64url(signer.sign(sa.private_key))}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${JSON.stringify(body).slice(0, 300)}`);
  return body.access_token;
}

const raw = extractBlock(readFileSync(ENV, "utf8"), "FIREBASE_SERVICE_ACCOUNT");
if (!raw) throw new Error("FIREBASE_SERVICE_ACCOUNT not found in dashboard/.env.local");
const sa = JSON.parse(repairJson(raw));
console.log("service account:", sa.client_email, "key id:", sa.private_key_id);

const token = await accessToken(sa);

/** GET the live template. The ETag is a response HEADER, not a body field. */
async function getTemplate() {
  const res = await fetch(API, { headers: { Authorization: `Bearer ${token}` } });
  const body = await res.json();
  if (!res.ok) throw new Error(`GET failed: ${res.status} ${JSON.stringify(body).slice(0, 400)}`);
  const etag = res.headers.get("etag");
  if (!etag) throw new Error("the GET response carried no ETag header — refusing to write without one");
  return { body, etag };
}

/**
 * The PUT body is built explicitly. The GET response also contains a
 * `version` object (the server's output); echoing it back is rejected by
 * the API, so only the editable parts are sent.
 */
function writableTemplate(template) {
  const out = { parameters: template.parameters ?? {} };
  if (template.conditions && template.conditions.length) out.conditions = template.conditions;
  // parameterGroups must survive the round trip: a group created in the
  // console would otherwise be deleted (with its parameters) by the write.
  if (template.parameterGroups && Object.keys(template.parameterGroups).length) {
    out.parameterGroups = template.parameterGroups;
  }
  return out;
}

/**
 * A short or empty template is a FAILED READ, not a template. Publishing it
 * would delete every live parameter (17 kill-switches, 17 base-URL
 * overrides, …) — and the API's validate_only dry run would happily accept
 * it, so the guard has to be on the READ.
 */
const MIN_PARAMETERS = 20;
const CRITICAL_PARAMETERS = [
  "source_starz_enabled",
  "source_azora_enabled",
  "source_starz_base_url",
  "scraper_selector_overrides",
  "community_banned_keywords",
];

function assertTemplateLooksComplete(params) {
  const names = Object.keys(params ?? {});
  if (names.length < MIN_PARAMETERS) {
    throw new Error(
      `refusing to publish: the live template has only ${names.length} parameters ` +
        `(expected at least ${MIN_PARAMETERS}) — a failed read must never delete the live parameters`,
    );
  }
  const missing = CRITICAL_PARAMETERS.filter((k) => !names.includes(k));
  if (missing.length) {
    throw new Error(
      `refusing to publish: the live template is missing the critical parameters ${missing.join(", ")}`,
    );
  }
}

async function put(template, etag, validateOnly) {
  const url = validateOnly ? `${API}?validate_only=true` : API;
  const res = await fetch(url, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify(writableTemplate(template)),
  });
  const out = await res.json();
  return { ok: res.ok, status: res.status, out };
}

const args = process.argv.slice(2);
const cmd = args[0] ?? "get";

if (cmd === "get") {
  const { body, etag } = await getTemplate();
  const params = body.parameters ?? {};
  for (const k of ["min_supported_version", "latest_version", "update_url"]) {
    const p = params[k];
    console.log(`${k}: ${p ? JSON.stringify(p) : "(NOT SET)"}`);
  }
  console.log("total parameters:", Object.keys(params).length);
  console.log("etag:", etag);
} else if (cmd === "validate") {
  // Dry run only: proves the write path without changing the live template.
  const min = args[1];
  const latest = args[2];
  if (!min) throw new Error("usage: node rc-gate.mjs validate <min> [latest]");
  const { body, etag } = await getTemplate();
  assertTemplateLooksComplete(body.parameters);
  const params = (body.parameters ??= {});
  const setParam = (name, value) => {
    params[name] = { ...(params[name] ?? {}), defaultValue: { value } };
  };
  setParam("min_supported_version", min);
  if (latest) setParam("latest_version", latest);
  const check = await put(body, etag, true);
  if (!check.ok) {
    console.error("VALIDATION failed:", check.status, JSON.stringify(check.out).slice(0, 500));
    process.exit(1);
  }
  console.log("validation OK — nothing was published (live template untouched)");
} else if (cmd === "set") {
  const min = args[1];
  const latest = args[2];
  const updateUrl = args[3];
  if (!min) throw new Error("usage: node rc-gate.mjs set <min_supported_version> [latest_version] [update_url]");
  const { body, etag } = await getTemplate();
  assertTemplateLooksComplete(body.parameters);
  const params = (body.parameters ??= {});
  const setParam = (name, value) => {
    params[name] = { ...(params[name] ?? {}), defaultValue: { value } };
  };
  setParam("min_supported_version", min);
  if (latest) setParam("latest_version", latest);
  if (updateUrl) setParam("update_url", updateUrl);

  // Dry run first: a malformed template must never reach the live project.
  const check = await put(body, etag, true);
  if (!check.ok) {
    console.error("VALIDATION failed — nothing was published:", check.status, JSON.stringify(check.out).slice(0, 500));
    process.exit(1);
  }
  console.log("validation OK");

  const res = await put(body, etag, false);
  if (!res.ok) {
    console.error("PUT failed:", res.status, JSON.stringify(res.out).slice(0, 500));
    process.exit(1);
  }
  console.log(
    "published: min_supported_version =", min,
    latest ? `, latest_version = ${latest}` : "",
    updateUrl ? `, update_url = ${updateUrl}` : "",
  );
  console.log("new etag:", res.out.etag ?? "(not echoed)");
} else {
  console.error("usage: node rc-gate.mjs get | validate <min> [latest] | set <min> [latest]");
  process.exit(1);
}
