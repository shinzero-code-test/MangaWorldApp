import { cookies } from "next/headers";
import { adminAuth, adminDb } from "./firebase-admin";
import { DecodedIdToken } from "firebase-admin/auth";

export interface AuthUser {
  uid: string;
  email: string | null;
  role: "super-admin" | "moderator" | "viewer";
}

export async function verifySession(): Promise<DecodedIdToken> {
  const cookieStore = await cookies();
  const session = cookieStore.get("session")?.value;
  if (!session) throw new Error("Unauthorized");
  return adminAuth.verifySessionCookie(session);
}

export async function getCurrentUser(): Promise<AuthUser> {
  const decoded = await verifySession();
  const doc = await adminDb.collection("publicProfiles").doc(decoded.uid).get();
  const data = doc.data();
  return {
    uid: decoded.uid,
    email: decoded.email,
    role: (data?.role as AuthUser["role"]) || "viewer",
  };
}

export async function requireRole(
  minRole: "viewer" | "moderator" | "super-admin"
): Promise<AuthUser> {
  const user = await getCurrentUser();
  const hierarchy = { viewer: 0, moderator: 1, "super-admin": 2 };
  if (hierarchy[user.role] < hierarchy[minRole]) {
    throw new Error("Forbidden");
  }
  return user;
}

export async function createSessionCookie(idToken: string): Promise<string> {
  const decoded = await adminAuth.verifyIdToken(idToken);

  // Auto-bootstrap: create profile if doesn't exist
  const profileDoc = await adminDb
    .collection("publicProfiles")
    .doc(decoded.uid)
    .get();
  if (!profileDoc.exists) {
    const userRecord = await adminAuth.getUser(decoded.uid);
    const isSuperAdmin =
      userRecord.email === process.env.SUPER_ADMIN_EMAIL;
    await adminDb.collection("publicProfiles").doc(decoded.uid).set({
      uid: decoded.uid,
      username: userRecord.email?.split("@")[0] || "unknown",
      role: isSuperAdmin ? "super-admin" : "viewer",
      isPublic: false,
      showListsPublic: false,
      showActivityPublic: false,
      bio: "",
      updatedAt: Date.now(),
    });
  }

  const expiresIn = 60 * 60 * 24 * 7 * 1000; // 7 days
  const cookie = await adminAuth.createSessionCookie(idToken, { expiresIn });
  return cookie;
}
