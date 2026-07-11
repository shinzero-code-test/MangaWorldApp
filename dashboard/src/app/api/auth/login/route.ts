import { NextRequest, NextResponse } from "next/server";
import { clearMfaGrantCookie } from "@/lib/auth";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const { email, password } = await request.json();

    const apiKey = process.env.NEXT_PUBLIC_FIREBASE_CLIENT_API_KEY ||
                   process.env.NEXT_PUBLIC_FIREBASE_API_KEY;
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
    const decoded = await getAdminAuth().verifyIdToken(idToken);

    const profileDoc = await getAdminDb().collection("publicProfiles").doc(decoded.uid).get();
    if (!profileDoc.exists) {
      await getAdminDb().collection("publicProfiles").doc(decoded.uid).set({
        username: email.split("@")[0],
        isPublic: false,
        bio: "",
        updatedAt: Date.now(),
      });
    }

    const expiresIn = 60 * 60 * 24 * 7 * 1000;
    const sessionCookie = await getAdminAuth().createSessionCookie(idToken, { expiresIn });

    const response = NextResponse.json({ success: true });
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });
    clearMfaGrantCookie(response);

    return response;
  } catch (error) {
    console.error("Dashboard password login error:", error);
    return NextResponse.json({ error: "خطأ في تسجيل الدخول" }, { status: 500 });
  }
}

export async function DELETE() {
  const response = NextResponse.json({ success: true });
  response.cookies.set("session", "", { httpOnly: true, secure: true, maxAge: 0, path: "/" });
  clearMfaGrantCookie(response);
  return response;
}
