import { cookies }                             from "next/headers";
import { getAdminAuth, getAdminDb }            from "./firebase-admin";

export interface AuthUser { uid:string; email:string; role:string; }

const ROLE_RANK: Record<string,number> = { viewer:0, moderator:1, "super-admin":2 };

export async function getCurrentUser(): Promise<AuthUser> {
  const store   = await cookies();
  const session = store.get("session")?.value;
  if (!session) throw new Error("Unauthorized");

  let decoded;
  try { decoded = await getAdminAuth().verifySessionCookie(session, true); }
  catch { throw new Error("Session expired or invalid"); }

  const uid        = decoded.uid;
  const profileDoc = await getAdminDb().collection("publicProfiles").doc(uid).get();
  const role       = profileDoc.data()?.role ?? "viewer";
  return { uid, email: decoded.email ?? "", role };
}

export async function requireRole(minRole: string): Promise<AuthUser> {
  const user = await getCurrentUser();
  if ((ROLE_RANK[user.role] ?? 0) < (ROLE_RANK[minRole] ?? 0)) throw new Error(`Forbidden`);
  return user;
}
