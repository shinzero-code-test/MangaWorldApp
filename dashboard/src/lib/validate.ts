/**
 * Hand-rolled body validators for admin mutation endpoints (M-6).
 * Zero-dependency by design — the dashboard runs on Vercel's Node runtime and
 * every helper here is cheap enough for per-request use.
 */

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Returns a trimmed string within [1, max], or null when invalid. */
export function boundedString(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (trimmed.length < 1 || trimmed.length > max) return null;
  return trimmed;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function isValidEmail(value: unknown): value is string {
  return typeof value === "string" && value.length <= 320 && EMAIL_RE.test(value);
}

/** Remote Config keys: conservative identifier charset, bounded length. */
const RC_KEY_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

// ─── U-30: source_*_base_url allow-list ─────────────────────────────────────
//
// A dashboard/RC compromise used to reach arbitrary https hosts: the app's
// normalizeBaseUrl enforces origin-only, not an allow-list. The server now
// enforces https + origin-only + per-id allow-list on every PUT that can
// write these keys, so a non-allow-listed host fails 400 before publish.
//
// Allow-list hosts mirror the RC UI defaults
// (dashboard/src/app/dashboard/remote-config/page.tsx SOURCE_DOMAINS) and
// the published plugin manifests (hijala/lavascans base hosts). A TRUE
// domain move therefore ships as a reviewed allow-list edit + dashboard
// deploy — the intended tradeoff: RC alone can never redirect a source to
// an unreviewed host. To "clear" an override, publish the id's default
// host (allow-listed); empty values are refused, not treated as clear.
const SOURCE_BASE_URL_KEY_RE = /^source_([a-z0-9][a-z0-9_-]{0,63})_base_url$/;
const SOURCE_BASE_HOST_RE =
  /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$/;

export const SOURCE_BASE_URL_ALLOWLIST: Record<string, string[]> = {
  olympus: ["olympustaff.com"],
  azora: ["azorafly.com"],
  starz: ["starzmanga.com"],
  mangasid: ["mangasid.com"],
  meshmanga: ["meshmanga.com"],
  asq3: ["3asq.online"],
  lekmanga: ["mangalik.net"],
  lekmangaonline: ["lekmanga.online"],
  likemanga: ["like-manga.net"],
  linkmanga: ["link-manga.net"],
  mangaleko: ["manga-leko.site"],
  mangalionz: ["manga-lionz.org"],
  areascans: ["ar.kenmanga.com"],
  hijala: ["hijala.com"],
  lavascans: ["lavascans.com"],
  stellarsaber: ["stellarsaber.pro"],
  procomic: ["procomic.pro"],
};

// ─── U-30 residual: API-host overrides (`source_<id>_api_host`) ─────────────
//
// The app reads three RC-overridable API hosts (U-25):
//   source_azora_api_host    → api.azorafly.com
//   source_mangasid_api_host → api.mangasid.com
//   source_meshmanga_api_host→ appswat.com
// They are the SAME content-spoofing channel as `source_<id>_base_url` (the
// app fetches chapter lists/images from them), so they get the same
// server-side allow-list. Without this, an RC compromise could repoint the
// Azora API at an arbitrary host — the base-URL gate did not cover it.
//
// Host-only values (`api.azorafly.com`), mirroring the app's
// SourceDomainOverrides.normalizeApiHost (host or https://host; no path,
// port, query or fragment).
export const SOURCE_API_HOST_ALLOWLIST: Record<string, string[]> = {
  azora: ["api.azorafly.com"],
  mangasid: ["api.mangasid.com"],
  meshmanga: ["appswat.com"],
};

const SOURCE_API_HOST_KEY_RE = /^source_([a-z0-9][a-z0-9_-]{0,63})_api_host$/;
const API_HOST_RE = /^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$/;

function normalizeAllowlistHost(host: string): string {
  return host.toLowerCase().replace(/^www\./, "");
}

/**
 * Validates every `source_<id>_base_url` entry in a Remote-Config parameter
 * map: https origin only (no credentials, non-default ports, path, query,
 * fragment) and host allow-listed for that id (www.-prefix-insensitive).
 * Non-matching keys pass untouched — this is an additive gate on top of
 * validateRemoteConfigParams. Call AFTER that validator; both PUTs
 * (remote-config + settings) run it before publishTemplate.
 */
export function validateSourceBaseUrlParams(parameters: unknown): ParamValidationResult {
  if (!isPlainObject(parameters)) {
    return { ok: true }; // shape errors are validateRemoteConfigParams' job
  }
  for (const [key, value] of Object.entries(parameters)) {
    const match = SOURCE_BASE_URL_KEY_RE.exec(key);
    if (!match) continue;
    const id = match[1];
    if (typeof value !== "string") {
      return { ok: false, error: `parameter ${key} must be an https origin URL` };
    }
    const trimmed = value.trim();
    if (trimmed.length === 0 || trimmed.length > 2048) {
      return { ok: false, error: `parameter ${key} exceeds 2048 characters` };
    }
    let url: URL;
    try {
      url = new URL(trimmed);
    } catch {
      return { ok: false, error: `parameter ${key} is not a valid URL` };
    }
    if (url.protocol !== "https:") {
      return { ok: false, error: `parameter ${key} must use https` };
    }
    if (url.username || url.password) {
      return { ok: false, error: `parameter ${key} must not contain credentials` };
    }
    if (url.port && url.port !== "443") {
      return { ok: false, error: `parameter ${key} must not contain a non-default port` };
    }
    if (url.pathname !== "/" && url.pathname !== "") {
      return { ok: false, error: `parameter ${key} must be an origin URL (no path)` };
    }
    if (url.search || url.hash) {
      return { ok: false, error: `parameter ${key} must be an origin URL (no query/fragment)` };
    }
    const hostRaw = url.hostname.toLowerCase();
    if (!SOURCE_BASE_HOST_RE.test(hostRaw)) {
      return { ok: false, error: `parameter ${key} has invalid host` };
    }
    const allowed = SOURCE_BASE_URL_ALLOWLIST[id];
    if (!allowed) {
      return { ok: false, error: `parameter ${key} references unknown source` };
    }
    const hostNorm = normalizeAllowlistHost(hostRaw);
    if (!allowed.map(normalizeAllowlistHost).includes(hostNorm)) {
      return { ok: false, error: `parameter ${key} host not allow-listed for source ${id}` };
    }
  }
  return { ok: true };
}

interface ParamValidationResult {
  ok: boolean;
  error?: string;
}

/**
 * Validates every `source_<id>_api_host` entry in a Remote-Config parameter
 * map: host-only (or an https://host form, which the app strips), literal
 * hostname, and allow-listed FOR THAT ID. Non-matching keys pass untouched.
 *
 * Mirrors the app's `SourceDomainOverrides.normalizeApiHost` (reject paths,
 * ports, query and fragment) but fails CLOSED where the app fails open
 * (the app silently ignores an invalid value and keeps its constant) — the
 * server must not publish a value the app would ignore.
 */
export function validateSourceApiHostParams(parameters: unknown): ParamValidationResult {
  if (!isPlainObject(parameters)) {
    return { ok: true }; // shape errors are validateRemoteConfigParams' job
  }
  for (const [key, value] of Object.entries(parameters)) {
    const match = SOURCE_API_HOST_KEY_RE.exec(key);
    if (!match) continue;
    const id = match[1];
    if (typeof value !== "string") {
      return { ok: false, error: `parameter ${key} must be a host string` };
    }
    // Trailing slashes are stripped (the app trims them too). NOTE: no
    // trimEnd() — the dashboard's TS lib is ES2017 and has no ES2019
    // String.prototype.trimEnd.
    const trimmed = value.trim().replace(/\/+$/, "").trim();
    if (trimmed.length === 0 || trimmed.length > 2048) {
      return { ok: false, error: `parameter ${key} exceeds 2048 characters` };
    }
    // The app strips one leading scheme prefix before validating the host.
    const host = trimmed.replace(/^https?:\/\//, "").toLowerCase();
    if (!API_HOST_RE.test(host) || host.includes("..")) {
      return { ok: false, error: `parameter ${key} is not a valid host` };
    }
    const allowed = SOURCE_API_HOST_ALLOWLIST[id];
    if (!allowed) {
      return { ok: false, error: `parameter ${key} references unknown source` };
    }
    if (!allowed.includes(host)) {
      return { ok: false, error: `parameter ${key} host not allow-listed for source ${id}` };
    }
  }
  return { ok: true };
}

/**
 * Single entry point for the source-domain gates: base URLs AND API hosts.
 * Both PUTs call THIS (never one of the halves) so a new source-domain key
 * cannot be added to one channel and forgotten in the other.
 */
export function validateSourceDomainParams(parameters: unknown): ParamValidationResult {
  const base = validateSourceBaseUrlParams(parameters);
  if (!base.ok) return base;
  return validateSourceApiHostParams(parameters);
}

/**
 * Validates a Remote-Config parameter map before it reaches the live template:
 * known key charset, primitive values only, bounded sizes. Prevents config
 * injection via attacker-chosen keys or oversized payloads.
 */
export function validateRemoteConfigParams(parameters: unknown): ParamValidationResult {
  if (!isPlainObject(parameters)) {
    return { ok: false, error: "parameters must be an object" };
  }
  const keys = Object.keys(parameters);
  if (keys.length === 0 || keys.length > 200) {
    return { ok: false, error: "parameters count out of range (1–200)" };
  }
  for (const key of keys) {
    if (!RC_KEY_RE.test(key)) {
      return { ok: false, error: `invalid parameter key: ${key.slice(0, 24)}` };
    }
    const value = parameters[key];
    if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") {
      return { ok: false, error: `parameter ${key} must be string/number/boolean` };
    }
    if (typeof value === "string" && value.length > 2000) {
      return { ok: false, error: `parameter ${key} exceeds 2000 characters` };
    }
  }
  return { ok: true };
}

/** Firestore document payload guard for the super-admin data browser. */
export function validateFirestoreDoc(data: unknown): ParamValidationResult {
  if (!isPlainObject(data)) {
    return { ok: false, error: "data must be a plain object" };
  }
  const json = JSON.stringify(data);
  if (json.length > 100_000) {
    return { ok: false, error: "document too large (100KB max)" };
  }
  // Recursive: nested {"a":{"__proto__":{}}} and dotted "a.b" keys (nested
  // paths under merge:true) must not slip past a top-level-only check.
  const stack: unknown[] = [data];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!isPlainObject(node)) continue;
    for (const key of Object.keys(node)) {
      if (key.startsWith("__")) {
        return { ok: false, error: `reserved field name: ${key.slice(0, 16)}` };
      }
      if (key.includes(".")) {
        return { ok: false, error: `dotted field name: ${key.slice(0, 16)}` };
      }
      stack.push((node as Record<string, unknown>)[key]);
    }
  }
  return { ok: true };
}

/** Cloudinary folder whitelist: relative path segments only, no traversal. */
const FOLDER_RE = /^[a-zA-Z0-9][a-zA-Z0-9_\-\/]{0,63}$/;

export function isValidFolder(folder: unknown): folder is string {
  return typeof folder === "string" && FOLDER_RE.test(folder) && !folder.includes("//") && !folder.includes("..");
}
