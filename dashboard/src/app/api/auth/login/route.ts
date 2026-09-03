import { NextRequest, NextResponse } from "next/server";
import { clearMfaGrantCookie, deleteCurrentMfaGrant } from "@/lib/auth";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { consumeRateLimit } from "@/lib/security";

export const dynamic = 'force-dynamic';

function clientIp(request: NextRequest): string {
  return (
    request.headers.get("x-real-ip")?.split(",")[0]?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    "unknown"
  );
}

export async function POST(request: NextRequest) {
  try {
    const { email, password } = await request.json();
    if (typeof email !== "string" || typeof password !== "string" || !email || !password) {
      return NextResponse.json({ error: "بيانات غير صالحة" }, { status: 400 });
    }

    // Credential-stuffing throttle: keyed per IP and per email before we ever
    // hit Identity Toolkit (M-1).
    const ipAttempt = await consumeRateLimit("login-ip", clientIp(request), 20, 15 * 60 * 1000);
    const emailAttempt = await consumeRateLimit(
      "login-email",
      email.trim().toLowerCase().replace(/[^a-zA-Z0-9@._\-]/g, "_"),
      10,
      15 * 60 * 1000
    );
    if (!ipAttempt.allowed || !emailAttempt.allowed) {
      return NextResponse.json(
        { error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." },
        { status: 429 }
      );
    }

    // Canonical client key — the NEXT_PUBLIC_FIREBASE_API_KEY alias was removed to avoid drift.
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
    const decoded = await getAdminAuth().verifyIdToken(idToken);

    // Block viewers from accessing the dashboard
    const userRole = decoded.role;
    if (!userRole || userRole === "viewer") {
      return NextResponse.json(
        { error: "ليس لديك صلاحية الوصول إلى لوحة التحكم. هذه اللوحة مخصصة للمشرفين والمديرين فقط." },
        { status: 403 }
      );
    }

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
    const isProd = process.env.NODE_ENV === "production";
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: isProd,
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });
    clearMfaGrantCookie(response);

    return response;
  } catch (error) {
    console.error("[login] failure:", error instanceof Error ? error.message : error);
    return NextResponse.json({ error: "خطأ في تسجيل الدخول" }, { status: 500 });
  }
}

export async function DELETE() {
  // Revoke the server-side grant too, so a captured cookie value cannot be
  // replayed for the remainder of its TTL after logout.
  await deleteCurrentMfaGrant();
  const response = NextResponse.json({ success: true });
  const isProd = process.env.NODE_ENV === "production";
  response.cookies.set("session", "", { httpOnly: true, secure: isProd, sameSite: "lax", maxAge: 0, path: "/" });
  clearMfaGrantCookie(response);
  return response;
}
