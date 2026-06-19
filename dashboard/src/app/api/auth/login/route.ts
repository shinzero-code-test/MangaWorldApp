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
    const { email, password } = await request.json();

    // Verify credentials by creating a custom token
    const userRecord = await adminAuth.getUserByEmail(email);
    
    // Create a custom token for the client to sign in with
    const customToken = await adminAuth.createCustomToken(userRecord.uid);

    // We need to verify the password server-side
    // Firebase Admin doesn't have a direct password verify, so we use
    // the Firebase Auth REST API
    const apiKey = process.env.NEXT_PUBLIC_FIREBASE_CLIENT_API_KEY;
    const signInRes = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password, returnSecureToken: true }),
      }
    );

    if (!signInRes.ok) {
      return NextResponse.json({ error: "بيانات تسجيل الدخول غير صحيحة" }, { status: 401 });
    }

    const { idToken } = await signInRes.json();

    // Verify the ID token
    const decoded = await adminAuth.verifyIdToken(idToken);

    // Auto-bootstrap profile
    const profileDoc = await adminDb.collection("publicProfiles").doc(decoded.uid).get();
    if (!profileDoc.exists) {
      const isSuperAdmin = email === process.env.SUPER_ADMIN_EMAIL;
      await adminDb.collection("publicProfiles").doc(decoded.uid).set({
        uid: decoded.uid,
        username: email.split("@")[0],
        role: isSuperAdmin ? "super-admin" : "viewer",
        isPublic: false,
        showListsPublic: false,
        showActivityPublic: false,
        bio: "",
        updatedAt: Date.now(),
      });
    }

    // Create session cookie
    const expiresIn = 60 * 60 * 24 * 7 * 1000;
    const sessionCookie = await adminAuth.createSessionCookie(idToken, { expiresIn });

    const response = NextResponse.json({ success: true });
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });

    return response;
  } catch (error: any) {
    console.error("Login error:", error);
    return NextResponse.json(
      { error: error.message || "خطأ في تسجيل الدخول" },
      { status: 500 }
    );
  }
}
