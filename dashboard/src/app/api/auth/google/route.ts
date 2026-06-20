import { NextRequest, NextResponse } from "next/server";
import { initializeApp, getApps, cert } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const app = getApps().length === 0
  ? initializeApp({ credential: cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT!)) })
  : getApps()[0];
const adminAuth = getAuth(app);
const adminDb = getFirestore(app);

export async function POST(request: NextRequest) {
  try {
    const { idToken } = await request.json();
    if (!idToken) {
      return NextResponse.json({ error: "Missing ID token" }, { status: 400 });
    }

    // Verify the ID token from the client
    const decoded = await adminAuth.verifyIdToken(idToken);

    // Auto-bootstrap profile
    const profileDoc = await adminDb.collection("publicProfiles").doc(decoded.uid).get();
    const isSuperAdmin = decoded.email === process.env.SUPER_ADMIN_EMAIL;

    if (!profileDoc.exists) {
      await adminDb.collection("publicProfiles").doc(decoded.uid).set({
        uid: decoded.uid,
        username: decoded.name || decoded.email?.split("@")[0] || "user",
        avatarUrl: decoded.picture || "",
        role: isSuperAdmin ? "super-admin" : "viewer",
        isPublic: false,
        showListsPublic: false,
        showActivityPublic: false,
        bio: "",
        updatedAt: Date.now(),
      });
    } else if (isSuperAdmin && profileDoc.data()?.role !== "super-admin") {
      await adminDb.collection("publicProfiles").doc(decoded.uid).update({
        role: "super-admin",
        updatedAt: Date.now(),
      });
    }

    // Create session cookie
    const expiresIn = 60 * 60 * 24 * 7 * 1000; // 7 days
    const sessionCookie = await adminAuth.createSessionCookie(idToken, { expiresIn });

    const response = NextResponse.json({ success: true, role: profileDoc.data()?.role || "viewer" });
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });

    return response;
  } catch (error: any) {
    console.error("Auth error:", error);
    return NextResponse.json(
      { error: error.message || "خطأ في المصادقة" },
      { status: 401 }
    );
  }
}
