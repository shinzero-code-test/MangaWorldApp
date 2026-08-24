import { createHmac, randomUUID } from "crypto";
import { cookies } from "next/headers";
import { getAdminAuth, getAdminDb } from "./firebase-admin";

export const DASHBOARD_ROLES = ["viewer", "moderator", "super-admin"] as const;
export type DashboardRole = (typeof DASHBOARD_ROLES)[number];
export interface AuthUser { uid: string; email: string; role: DashboardRole; mfaVerified: boolean; /** ms epoch of the session's original authentication — used for fresh-auth checks */ authTime?: number; }

const ROLE_RANK: Record<DashboardRole, number> = {
  viewer: 0,
  moderator: 1,
  "super-admin": 2,
};
const MFA_GRANT_COOKIE = "mfa_grant";
const MFA_GRANT_TTL_SECONDS = 8 * 60 * 60;

export class MfaRequiredError extends Error {}

export async function getCurrentUser(options: { requireMfa?: boolean } = {}): Promise<AuthUser> {
  const { session, uid, email, role, authTime } = await sessionContext();
  const mfaVerified = await hasValidMfaGrant(uid, session);
  if (options.requireMfa && !mfaVerified) throw new MfaRequiredError("MFA verification required");
  return { uid, email, role, mfaVerified, authTime };
}

export async function requireRole(minRole: DashboardRole): Promise<AuthUser> {
  const user = await getCurrentUser({ requireMfa: true });
  if (ROLE_RANK[user.role] < ROLE_RANK[minRole]) throw new Error("Forbidden");
  return user;
}

export async function getDashboardRoleCounts(): Promise<Record<DashboardRole, number>> {
  const counts: Record<DashboardRole, number> = { viewer: 0, moderator: 0, "super-admin": 0 };
  let pageToken: string | undefined;
  do {
    const page = await getAdminAuth().listUsers(1000, pageToken);
    page.users.forEach((user) => {
      const role = user.customClaims?.role;
      counts[isDashboardRole(role) ? role : "viewer"] += 1;
    });
    pageToken = page.pageToken;
  } while (pageToken);
  return counts;
}

export async function createMfaGrant(user: AuthUser): Promise<string> {
  const { session, uid } = await sessionContext();
  if (uid !== user.uid) throw new Error("Session mismatch");
  const grantId = randomUUID();
  await getAdminDb().collection("adminMfaSessions").doc(grantId).set({
    uid,
    sessionFingerprint: sessionFingerprint(session),
    expiresAt: Date.now() + MFA_GRANT_TTL_SECONDS * 1000,
    verifiedAt: Date.now(),
  });
  // Opportunistic hygiene: purge up to 20 expired grants so revoked logouts and
  // rotated sessions don't leave replayable rows behind until their TTL lapses.
  try {
    const expired = await getAdminDb()
      .collection("adminMfaSessions")
      .where("expiresAt", "<", Date.now())
      .limit(20)
      .get();
    if (!expired.empty) {
      const batch = getAdminDb().batch();
      expired.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
    }
  } catch {
    /* non-fatal */
  }
  return grantId;
}

/** Deletes the caller's current MFA grant document — used on logout (L-3). */
export async function deleteCurrentMfaGrant(): Promise<void> {
  try {
    const grantId = (await cookies()).get(MFA_GRANT_COOKIE)?.value;
    if (!grantId) return;
    await getAdminDb().collection("adminMfaSessions").doc(grantId).delete();
  } catch {
    /* non-fatal */
  }
}

export function setMfaGrantCookie(response: Response, grantId: string) {
  // NextResponse extends Response and exposes the cookie API at runtime.
  const nextResponse = response as Response & { cookies: { set: (name: string, value: string, options: object) => void } };
  nextResponse.cookies.set(MFA_GRANT_COOKIE, grantId, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    maxAge: MFA_GRANT_TTL_SECONDS,
    path: "/",
  });
}

export function clearMfaGrantCookie(response: Response) {
  const nextResponse = response as Response & { cookies: { set: (name: string, value: string, options: object) => void } };
  nextResponse.cookies.set(MFA_GRANT_COOKIE, "", { httpOnly: true, secure: true, maxAge: 0, path: "/" });
}

async function sessionContext(): Promise<{ session: string; uid: string; email: string; role: DashboardRole; authTime?: number }> {
  const store = await cookies();
  const session = store.get("session")?.value;
  if (!session) throw new Error("Unauthorized");

  let decoded;
  try {
    decoded = await getAdminAuth().verifySessionCookie(session, true);
  } catch {
    throw new Error("Session expired or invalid");
  }
  const role = decoded.role;
  if (role !== undefined && !isDashboardRole(role)) throw new Error("Invalid dashboard role");
  // auth_time (seconds) marks the original credential authentication — enables
  // "fresh re-auth" checks for sensitive flows such as 2FA enrollment.
  const authTime = typeof decoded.auth_time === "number" ? decoded.auth_time * 1000 : undefined;
  return { session, uid: decoded.uid, email: decoded.email ?? "", role: role ?? "viewer", authTime };
}

async function hasValidMfaGrant(uid: string, session: string): Promise<boolean> {
  const mfaConfig = await getAdminDb().collection("admin2fa").doc(uid).get();
  if (!mfaConfig.exists || mfaConfig.data()?.enabled !== true) return false;

  const grantId = (await cookies()).get(MFA_GRANT_COOKIE)?.value;
  if (!grantId) return false;
  const grant = await getAdminDb().collection("adminMfaSessions").doc(grantId).get();
  const data = grant.data();
  return data?.uid === uid
    && data.sessionFingerprint === sessionFingerprint(session)
    && typeof data.expiresAt === "number"
    && data.expiresAt > Date.now();
}

function sessionFingerprint(session: string): string {
  const secret = process.env.MFA_SESSION_SECRET;
  if (!secret) throw new Error("MFA_SESSION_SECRET is not configured");
  return createHmac("sha256", secret).update(session).digest("hex");
}

function isDashboardRole(value: unknown): value is DashboardRole {
  return typeof value === "string" && DASHBOARD_ROLES.includes(value as DashboardRole);
}
