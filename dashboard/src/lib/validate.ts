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

interface ParamValidationResult {
  ok: boolean;
  error?: string;
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
  for (const key of Object.keys(data)) {
    if (key.startsWith("__")) {
      return { ok: false, error: `reserved field name: ${key.slice(0, 16)}` };
    }
  }
  return { ok: true };
}

/** Cloudinary folder whitelist: relative path segments only, no traversal. */
const FOLDER_RE = /^[a-zA-Z0-9][a-zA-Z0-9_\-\/]{0,63}$/;

export function isValidFolder(folder: unknown): folder is string {
  return typeof folder === "string" && FOLDER_RE.test(folder) && !folder.includes("//") && !folder.includes("..");
}
