import { getAdminDb } from "./firebase-admin";

/**
 * Dashboard mirror of Android `UsernameRules` (domain/UsernameRules.kt).
 *
 * Single source of truth for dashboard-side username validation +
 * provisioning. Previously every dashboard route inlined its own ad-hoc
 * rule (`email.split("@")[0]`, `decoded.name || ...`, length 1..64) with no
 * `usernames/{username}` claim — dashboard-provisioned names could be
 * invalid per the app regex or collide with the app claim system (G/D-3).
 *
 * Firestore claim shape (must match Android + firestore.rules):
 *   usernames/{normalized} = { uid, username, updatedAt }
 * where `normalized` = `username.trim().toLowerCase()`.
 */

export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 20;
export const USERNAME_RE = /^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$/;

export function isValidUsername(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length >= USERNAME_MIN_LENGTH &&
    value.length <= USERNAME_MAX_LENGTH &&
    USERNAME_RE.test(value)
  );
}

export function normalizeUsername(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * Sanitizes an arbitrary candidate (email local-part, OAuth display name)
 * into a valid username. Mirrors Android `usernameFromEmail`:
 * lowercase → invalid chars to `_` → collapse runs → trim `_` → cap at 20.
 * Falls back to a uid-derived `user_xxxxxx` when nothing usable remains
 * (CJK-only local parts, single-char names, …).
 */
export function sanitizeUsernameCandidate(candidate: unknown, fallbackUid: string): string {
  const raw = typeof candidate === "string" ? candidate.toLowerCase() : "";
  const cleaned = raw
    .split("")
    .map((ch) => (/[a-z0-9]/.test(ch) ? ch : "_"))
    .join("")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, USERNAME_MAX_LENGTH)
    .replace(/^_+|_+$/g, "");
  if (isValidUsername(cleaned)) return cleaned;
  const uidPart = fallbackUid
    .toLowerCase()
    .split("")
    .filter((ch) => /[a-z0-9]/.test(ch))
    .join("")
    .slice(-6);
  const fallback = `user_${uidPart}`;
  if (isValidUsername(fallback)) return fallback;
  return `user_${Math.floor(10000 + Math.random() * 90000)}`;
}

/** Derives the sanitize input from an email address (local-part). */
export function usernameBaseFromEmail(email: unknown, fallbackUid: string): string {
  const local = typeof email === "string" ? email.split("@")[0] ?? "" : "";
  return sanitizeUsernameCandidate(local, fallbackUid);
}

/**
 * Claims `usernames/{normalized}` for `uid` iff free or already owned.
 * Returns true when the caller now owns the name. Never throws — callers
 * fall back to a suffixed retry on false.
 */
export async function tryClaimUsername(
  uid: string,
  username: string
): Promise<boolean> {
  if (!isValidUsername(username)) return false;
  const normalized = normalizeUsername(username);
  const db = getAdminDb();
  return db.runTransaction(async (tx) => {
    const ref = db.collection("usernames").doc(normalized);
    const snap = await tx.get(ref);
    const owner = snap.data()?.uid;
    if (snap.exists && owner !== uid) return false;
    tx.set(ref, { uid, username, updatedAt: Date.now() });
    return true;
  });
}

/**
 * Releases a username claim iff owned by `uid`. Used on admin delete (D-5)
 * so a dead uid never permanently reserves a name.
 */
export async function releaseUsernameIfOwned(uid: string, username: unknown): Promise<void> {
  if (typeof username !== "string" || !username) return;
  const normalized = normalizeUsername(username);
  if (!normalized) return;
  try {
    const db = getAdminDb();
    await db.runTransaction(async (tx) => {
      const ref = db.collection("usernames").doc(normalized);
      const snap = await tx.get(ref);
      if (snap.exists && snap.data()?.uid === uid) tx.delete(ref);
    });
  } catch {
    /* best-effort cleanup must never fail the request path */
  }
}

/**
 * Ensures `publicProfiles/{uid}` exists with a valid, claimed username.
 * - Existing doc with a valid username: claims it if unclaimed (repairs
 *   pre-fix dashboard rows that skipped the claim) and returns it.
 * - Missing/invalid: sanitizes `candidate`, retries with `_NNNN` suffix
 *   (room left by truncating the base to 15 chars, mirroring Android),
 *   writes the profile with `{ merge: true }` so existing bio/avatar survive.
 *
 * Never throws — returns the username that was ensured, or a best-effort
 * sanitized value when Firestore is unavailable (profile write skipped).
 */
export async function ensureDashboardProfile(
  uid: string,
  candidate: unknown,
  extra: Record<string, unknown> = {}
): Promise<string> {
  const db = getAdminDb();
  try {
    const profileRef = db.collection("publicProfiles").doc(uid);
    const existing = await profileRef.get();
    const current = existing.data()?.username;
    if (typeof current === "string" && isValidUsername(current)) {
      // Repair path: pre-D-3 rows exist without a claim doc.
      await tryClaimUsername(uid, current).catch(() => false);
      return current;
    }
    const base = sanitizeUsernameCandidate(
      typeof candidate === "string" && candidate ? candidate : `user_${uid.slice(-6)}`,
      uid
    );
    let username = base;
    for (let attempt = 0; attempt < 4; attempt++) {
      if (await tryClaimUsername(uid, username).catch(() => false)) break;
      username = `${base.slice(0, 15)}_${Math.floor(1000 + Math.random() * 9000)}`;
      if (attempt === 3) return base;
    }
    await profileRef.set(
      {
        username,
        isPublic: false,
        bio: "",
        updatedAt: Date.now(),
        ...extra,
      },
      { merge: true }
    );
    return username;
  } catch {
    return sanitizeUsernameCandidate(
      typeof candidate === "string" ? candidate : "",
      uid
    );
  }
}
