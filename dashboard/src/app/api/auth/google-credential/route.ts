import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth } from "@/lib/firebase-admin";
import { consumeRateLimit, genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

// Public Google OAuth client ID (identifier only, not a secret) for the
// GIS button flow. Must match the Firebase-managed google.com provider.
const GOOGLE_CLIENT_ID = (process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ?? "").trim();

function clientIp(request: NextRequest): string {
  return (
    request.headers.get("x-real-ip")?.split(",")[0]?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    "unknown"
  );
}

interface GoogleTokenInfo {
  aud?: string;
  iss?: string;
  email?: string;
  email_verified?: string | boolean;
  name?: string;
  picture?: string;
  exp?: string;
  error_description?: string;
}

/**
 * POST /api/auth/google-credential { credential: <Google ID token> }
 *
 * GIS escape hatch for environments where the firebaseapp.com iframe relay
 * (popup AND redirect) is dead: the Google ID token is verified server-side
 * and exchanged for a Firebase custom token. The client then signs in with
 * signInWithCustomToken (direct Identity Toolkit call, no iframe) and follows
 * the normal /api/auth/google session pipeline, so roles/MFA/2FA are unchanged.
 */
export async function POST(request: NextRequest) {
  try {
    if (!GOOGLE_CLIENT_ID) {
      return NextResponse.json({ error: "تسجيل الدخول بـ Google غير مهيأ" }, { status: 500 });
    }
    const body: unknown = await request.json();
    const credential = typeof body === "object" && body !== null && "credential" in body
      ? (body as Record<string, unknown>).credential
      : null;
    if (typeof credential !== "string" || credential.length === 0 || credential.length > 8192) {
      return NextResponse.json({ error: "بيانات غير صالحة" }, { status: 400 });
    }

    const ipAttempt = await consumeRateLimit("gis-ip", clientIp(request), 20, 15 * 60 * 1000);
    if (!ipAttempt.allowed) {
      return NextResponse.json(
        { error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." },
        { status: 429 }
      );
    }

    // Server-to-server verification with Google. No new dependency needed at
    // admin-dashboard volume; the credential itself is never logged.
    const infoRes = await fetch(
      `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`,
      { method: "GET", cache: "no-store" }
    );
    if (!infoRes.ok) {
      return NextResponse.json({ error: "تعذر التحقق من حساب Google" }, { status: 401 });
    }
    const info = (await infoRes.json()) as GoogleTokenInfo;
    const email = (info.email ?? "").trim().toLowerCase();
    const emailVerified = info.email_verified === true || info.email_verified === "true";
    const validIssuer = info.iss === "https://accounts.google.com" || info.iss === "accounts.google.com";
    if (!email || !emailVerified || !validIssuer || info.aud !== GOOGLE_CLIENT_ID) {
      return NextResponse.json({ error: "تعذر التحقق من حساب Google" }, { status: 401 });
    }

    const adminAuth = getAdminAuth();
    let uid: string;
    try {
      uid = (await adminAuth.getUserByEmail(email)).uid;
    } catch (lookupError: unknown) {
      const code = typeof lookupError === "object" && lookupError !== null && "code" in lookupError
        ? String((lookupError as Record<string, unknown>).code)
        : "";
      if (code !== "auth/user-not-found") throw lookupError;
      // No open enrollment: unknown Google emails get a Firebase account only
      // when they match the configured super-admin (owner bootstrap). Any
      // other new admin must be pre-provisioned; otherwise arbitrary Google
      // users would accumulate orphan Auth accounts.
      const configuredSuperAdmin = process.env.SUPER_ADMIN_EMAIL?.trim().toLowerCase();
      if (!configuredSuperAdmin || email !== configuredSuperAdmin) {
        return NextResponse.json({ error: "تعذر تسجيل الدخول بـ Google" }, { status: 401 });
      }
      uid = (await adminAuth.createUser({
        email,
        emailVerified: true,
        displayName: typeof info.name === "string" ? info.name.slice(0, 128) : undefined,
        photoURL: typeof info.picture === "string" ? info.picture.slice(0, 2048) : undefined,
      })).uid;
    }
    // A Google-verified address must satisfy the email_verified gate used by
    // the super-admin auto-promotion in /api/auth/google.
    const current = await adminAuth.getUser(uid);
    if (!current.emailVerified) {
      await adminAuth.updateUser(uid, { emailVerified: true });
    }

    const customToken = await adminAuth.createCustomToken(uid);
    return NextResponse.json({ customToken });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
