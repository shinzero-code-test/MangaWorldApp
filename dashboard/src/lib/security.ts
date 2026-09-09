import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  scryptSync,
  timingSafeEqual,
} from "crypto";
import { getAdminDb } from "./firebase-admin";

/**
 * Shared server-side security helpers:
 *  - AES-256-GCM encryption for TOTP secrets at rest (H-2)
 *  - Constant-time TOTP verification (M-2)
 *  - Firestore-backed rate limiting / OTP lockout that survives serverless
 *    instance churn (H-1, M-1, M-3)
 *  - Security audit log + generic error responses (H-4, M-5)
 */

// ─── TOTP secret encryption ──────────────────────────────────────────────────

const SECRET_CIPHER = "aes-256-gcm";
const KDF_SALT = "mangaworld-admin-mfa-v1";

function mfaKey(): Buffer {
  const secret = process.env.MFA_SESSION_SECRET;
  if (!secret) throw new Error("MFA_SESSION_SECRET is not configured");
  return scryptSync(secret, KDF_SALT, 32);
}

export interface EncryptedSecret {
  v: 1;
  iv: string;
  tag: string;
  data: string;
}

export function encryptSecret(plain: string): EncryptedSecret {
  const iv = randomBytes(12);
  const cipher = createCipheriv(SECRET_CIPHER, mfaKey(), iv);
  const data = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return {
    v: 1,
    iv: iv.toString("hex"),
    tag: cipher.getAuthTag().toString("hex"),
    data: data.toString("hex"),
  };
}

export function decryptSecret(payload: EncryptedSecret): string {
  const decipher = createDecipheriv(
    SECRET_CIPHER,
    mfaKey(),
    Buffer.from(payload.iv, "hex")
  );
  decipher.setAuthTag(Buffer.from(payload.tag, "hex"));
  return Buffer.concat([
    decipher.update(Buffer.from(payload.data, "hex")),
    decipher.final(),
  ]).toString("utf8");
}

/**
 * Reads a TOTP secret from an admin2fa document, transparently supporting both
 * the encrypted-at-rest format ({v:1,iv,tag,data}) and legacy plaintext rows.
 */
export function resolveTotpSecret(docData: Record<string, unknown> | undefined): string | null {
  if (!docData) return null;
  const enc = docData.secretEncrypted as EncryptedSecret | undefined;
  if (enc && typeof enc.data === "string" && typeof enc.iv === "string" && typeof enc.tag === "string") {
    try {
      return decryptSecret(enc);
    } catch {
      return null;
    }
  }
  const legacy = docData.secret;
  return typeof legacy === "string" && legacy.length > 0 ? legacy : null;
}

/** Re-encrypts a legacy plaintext secret in place. Best-effort; never throws. */
export async function upgradeLegacySecret(uid: string, plain: string): Promise<void> {
  try {
    await getAdminDb()
      .collection("admin2fa")
      .doc(uid)
      .set({ secret: null, secretEncrypted: encryptSecret(plain) }, { merge: true });
  } catch {
    /* non-fatal */
  }
}

// ─── Constant-time TOTP verification ────────────────────────────────────────

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Decode(input: string): Buffer {
  const clean = input.toUpperCase().replace(/[=\s]/g, "");
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const char of clean) {
    const idx = BASE32_ALPHABET.indexOf(char);
    if (idx === -1) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** RFC 6238 TOTP (HMAC-SHA1, 30 s step, 6 digits) at an explicit point in time. */
function totpAt(secretBase32: string, timeMs: number): string {
  const key = base32Decode(secretBase32);
  if (key.length === 0) throw new Error("invalid TOTP secret");
  const counter = Math.floor(timeMs / 30_000);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const hmac = createHmac("sha1", key).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0xf;
  const binary =
    ((hmac[offset] & 0x7f) << 24) |
    (hmac[offset + 1] << 16) |
    (hmac[offset + 2] << 8) |
    hmac[offset + 3];
  return String(binary % 1_000_000).padStart(6, "0");
}

function safeEquals(a: string, b: string): boolean {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) {
    // Burn comparable time so length does not leak.
    timingSafeEqual(bufA, bufA);
    return false;
  }
  return timingSafeEqual(bufA, bufB);
}

/**
 * Verifies a TOTP token with constant-time comparison. otplib's own `verify()`
 * ends in a plain string equality check; we compute the expected code(s) here
 * and compare digests ourselves instead.
 */
export function verifyTotpConstantTime(
  secret: string,
  token: string,
  // ±1 step absorbs >30s clock skew that otherwise false-rejects into the
  // 5-fail lockout (availability self-DoS).
  window = 1
): boolean {
  const normalized = String(token ?? "").replace(/\s+/g, "");
  if (!/^\d{6}$/.test(normalized)) return false;
  const now = Date.now();
  for (let drift = -window; drift <= window; drift++) {
    let expected: string;
    try {
      expected = totpAt(secret, now + drift * 30_000);
    } catch {
      return false;
    }
    if (safeEquals(normalized, expected)) return true;
  }
  return false;
}

// ─── TOTP backup (recovery) codes ──────────────────────────────────────────
// Lost-authenticator recovery without Firestore surgery (#3). Codes are shown
// once at generation, stored only as SHA-256 hashes, and single-use: a
// successful validation deletes the hash. Fresh-auth gates generation and
// disable, so a stolen long-lived session alone cannot mint or kill them.

const BACKUP_CODE_COUNT = 10;
const BACKUP_CODE_BYTES = 8; // 64 bits → 8 unambiguous chars, one per byte
const BACKUP_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
const BACKUP_HASH_PREFIX = "mw-backup-v1:";

export function hashBackupCode(code: string): string {
  return createHash("sha256").update(BACKUP_HASH_PREFIX + code.trim().toUpperCase()).digest("hex");
}

export function generateBackupCodes(count: number = BACKUP_CODE_COUNT): { codes: string[]; hashes: string[] } {
  const codes: string[] = [];
  for (let i = 0; i < count; i++) {
    const bytes = randomBytes(BACKUP_CODE_BYTES);
    let code = "";
    for (const b of bytes) code += BACKUP_ALPHABET[b % BACKUP_ALPHABET.length];
    // 8 chars + unambiguous alphabet: collisions at count=10 are negligible,
    // but dedupe anyway so every issued set is unique.
    if (codes.includes(code)) {
      i--;
      continue;
    }
    codes.push(code);
  }
  return { codes, hashes: codes.map(hashBackupCode) };
}

/** Constant-time lookup: returns the matching stored hash, if any. */
export function matchBackupCodeHash(candidate: string, hashes: unknown): string | null {
  if (!Array.isArray(hashes)) return null;
  const want = hashBackupCode(candidate);
  for (const h of hashes) {
    if (typeof h === "string" && h.length === want.length && safeEquals(h, want)) return h;
  }
  return null;
}

// ─── Firestore-backed fixed-window rate limiting ─────────────────────────────

type LimitResult = { allowed: boolean };

function memKey(bucket: string, key: string) {
  return `${bucket}:${key}`;
}
const memoryFallback = new Map<string, { count: number; resetAt: number }>();

function memoryLimit(bucket: string, key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  const current = memoryFallback.get(memKey(bucket, key));
  if (!current || current.resetAt <= now) {
    memoryFallback.set(memKey(bucket, key), { count: 1, resetAt: now + windowMs });
    return true;
  }
  if (current.count >= limit) return false;
  current.count += 1;
  return true;
}

/**
 * Fixed-window counter stored in Firestore so the limit holds across serverless
 * instances. Falls back to per-instance memory if Firestore hiccups — weaker but
 * never worse than the previous behaviour.
 */
export async function consumeRateLimit(
  bucket: string,
  key: string,
  limit: number,
  windowMs: number
): Promise<LimitResult> {
  const docId = `${bucket}_${key}`.replace(/[^a-zA-Z0-9_\-]/g, "_").slice(0, 200);
  const now = Date.now();
  try {
    const allowed = await getAdminDb().runTransaction(async (tx) => {
      const ref = getAdminDb().collection("rateLimits").doc(docId);
      const snap = await tx.get(ref);
      const data = snap.data();
      if (!data || typeof data.resetAt !== "number" || data.resetAt <= now) {
        tx.set(ref, { count: 1, resetAt: now + windowMs });
        return true;
      }
      if ((data.count as number) >= limit) return false;
      tx.update(ref, { count: (data.count as number) + 1 });
      return true;
    });
    return { allowed };
  } catch {
    return { allowed: memoryLimit(bucket, key, limit, windowMs) };
  }
}

// ─── OTP attempt throttling (H-1) ────────────────────────────────────────────

const OTP_MAX_FAILURES = 5;
const OTP_LOCK_MS = 5 * 60 * 1000;

export async function isOtpLocked(uid: string): Promise<boolean> {
  const snap = await getAdminDb().collection("adminOtpAttempts").doc(uid).get();
  const lockedUntil = snap.data()?.lockedUntil;
  return typeof lockedUntil === "number" && lockedUntil > Date.now();
}

export async function recordOtpFailure(uid: string): Promise<{ locked: boolean }> {
  const ref = getAdminDb().collection("adminOtpAttempts").doc(uid);
  let count = 1;
  await getAdminDb().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const prev = snap.data();
    // A stale lockout window resets the counter.
    count = prev && (prev.lockedUntil ?? 0) > Date.now() ? (prev.count as number) + 1 : 1;
    tx.set(ref, {
      count,
      updatedAt: Date.now(),
      ...(count >= OTP_MAX_FAILURES ? { lockedUntil: Date.now() + OTP_LOCK_MS } : {}),
    });
  });
  const locked = count >= OTP_MAX_FAILURES;
  if (locked) await logSecurityEvent("otp_locked", { uid });
  return { locked };
}

export async function clearOtpFailures(uid: string): Promise<void> {
  await getAdminDb().collection("adminOtpAttempts").doc(uid).delete();
}

const USED_TOKEN_TTL_MS = 90_000;

/** Single-use guard: a still-valid code must not mint a second grant on repost. */
export async function consumeUsedToken(uid: string, token: string): Promise<boolean> {
  const { createHash } = await import("crypto");
  const digest = createHash("sha256").update(`${uid}:${token}`).digest("hex");
  const ref = getAdminDb().collection("adminUsedTokens").doc(digest);
  const claimed = await getAdminDb().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) return false;
    tx.set(ref, { at: Date.now(), expiresAt: Date.now() + USED_TOKEN_TTL_MS });
    return true;
  });
  return claimed;
}

// ─── Security audit log (H-4, M-7) ───────────────────────────────────────────

export async function logSecurityEvent(
  event: string,
  detail: Record<string, unknown>
): Promise<void> {
  try {
    await getAdminDb().collection("adminSecurityLog").add({
      event,
      detail,
      at: Date.now(),
    });
  } catch {
    /* auditing must never break the request path */
  }
}

// ─── Generic error responses (M-5) ───────────────────────────────────────────

let correlationCounter = 0;

/**
 * Logs full error details server-side under a correlation id and returns a
 * fixed client-facing message, so backend internals never leak in responses.
 */
export function genericErrorResponse(error: unknown, clientMessage = "حدث خطأ غير متوقع"): {
  body: { error: string; correlationId?: string };
  status: number;
} {
  const message = error instanceof Error ? error.message : "";
  if (message === "Forbidden") return { body: { error: "Forbidden" }, status: 403 };
  if (message === "Unauthorized" || message === "Session expired or invalid") {
    return { body: { error: message }, status: 401 };
  }
  if (message === "MFA verification required") {
    return { body: { error: message }, status: 403 };
  }
  const correlationId = `err_${Date.now().toString(36)}_${(++correlationCounter).toString(36)}`;
  console.error(`[dashboard-api ${correlationId}]`, error);
  return { body: { error: clientMessage, correlationId }, status: 500 };
}
